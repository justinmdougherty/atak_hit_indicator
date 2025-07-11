#include <SPI.h>
#include <LoRa.h>
#include <TinyGPS++.h>
#include <string.h>
#include <FlashStorage.h>

// LoRa radio pins
#define RFM95_CS 8
#define RFM95_INT 3
#define RFM95_RST 4
#define LORA_FREQUENCY 915E6

// Framing markers
#define START_MARKER '<'
#define END_MARKER '>'
#define MAX_MSG_LEN 128
#define MAX_CMD_LEN 32
#define MAX_ID_LEN 16
#define MAX_CSV_LEN 96

// Vibe sensor
#define VIBE_PIN A0
#define DEBOUNCE_MS 2000

#define BATTERY_PIN A1

TinyGPSPlus gps;

const double SMOOTH_ALPHA = 0.1; // 0=no smoothing, 1=freeze on first reading
static double smoothedLat = 0, smoothedLon = 0, smoothedAlt = 0;
static bool hasSmoothed = false;
static bool initialReportSent = false;

struct GpsPosition
{
  double lat;
  double lon;
  double alt;
  bool valid;
};

unsigned long lastHitTime = 0;
bool lastState = HIGH;

char targetIDBuf[MAX_ID_LEN] = "";
const char *targetID = targetIDBuf;

struct TargetIDWrapper
{
  char id[MAX_ID_LEN];
};
FlashStorage(targetStorage, TargetIDWrapper);

bool gpsPreviouslyValid = false;

void setup()
{
  Serial.begin(9600);
  Serial1.begin(9600);
  pinMode(VIBE_PIN, INPUT_PULLUP);
  pinMode(LED_BUILTIN, OUTPUT);
  delay(2000);
  Serial.println("Feather M0 LoRa Target Starting...");
  digitalWrite(LED_BUILTIN, HIGH);
  delay(200);
  digitalWrite(LED_BUILTIN, LOW);

  TargetIDWrapper stored;
  targetStorage.read(&stored);
  strncpy(targetIDBuf, stored.id, MAX_ID_LEN - 1);
  targetIDBuf[MAX_ID_LEN - 1] = '\0';

  if (targetIDBuf[0] == '\0' || targetIDBuf[0] == 0xFF)
  {
    // --- Begin new ID generation logic ---
    unsigned long seedValue = millis(); // Start with current milliseconds

// --- Optional: Incorporate analog noise for a better seed ---
// IMPORTANT: Ensure A0 is an unconnected analog pin to read noise.
// If A0 is used, choose another UNCONNECTED analog pin, or remove this section.
#define UNUSED_ANALOG_PIN A0           // Define which pin you are using for noise
    pinMode(UNUSED_ANALOG_PIN, INPUT); // Set to INPUT to ensure it's floating (not INPUT_PULLUP)
    // Reading a couple of times and XORing can sometimes help capture more entropy
    seedValue ^= (unsigned long)analogRead(UNUSED_ANALOG_PIN);
    delayMicroseconds(50);                                          // Small delay, allow ADC to settle if read again
    seedValue += (unsigned long)analogRead(UNUSED_ANALOG_PIN) << 8; // Add more noise, shifted

    randomSeed(seedValue); // Seed the pseudo-random number generator

    // Generate a 16-bit random number (0 to 65535)
    long idSuffix = random(0x10000); // random(max) gives a long from 0 to max-1. 0x10000 = 65536

    snprintf(targetIDBuf, MAX_ID_LEN, "ID%04lX", idSuffix); // Format as "ID" + 4 uppercase hex digits
    // --- End new ID generation logic ---

    // The rest of your existing code for printing and attempting to save follows:
    Serial.print("Default ID assigned: ");
    Serial.println(targetIDBuf);

    TargetIDWrapper defaultID;
    strncpy(defaultID.id, targetIDBuf, MAX_ID_LEN);
    targetStorage.write(defaultID);
  }
  else
  {
    Serial.print("Loaded ID: ");
    Serial.println(targetIDBuf);
  }

  LoRa.setPins(RFM95_CS, RFM95_RST, RFM95_INT);
  if (!LoRa.begin(LORA_FREQUENCY))
  {
    Serial.println("LoRa init failed. Check wiring.");
    while (true)
    {
      digitalWrite(LED_BUILTIN, HIGH);
      delay(100);
      digitalWrite(LED_BUILTIN, LOW);
      delay(100);
    }
  }
  Serial.println("LoRa init succeeded.");
  digitalWrite(LED_BUILTIN, HIGH);
  delay(500);
  digitalWrite(LED_BUILTIN, LOW);
}

void loop()
{
  int currentState = digitalRead(VIBE_PIN);
  unsigned long now = millis();

  if (lastState == HIGH && currentState == LOW)
  {
    if (now - lastHitTime > DEBOUNCE_MS)
    {
      lastHitTime = now;
      Serial.println("\xF0\x9F\x92\xA5 Vibe detected! Sending HIT.");
      sendHitMessage(targetID);
      digitalWrite(LED_BUILTIN, HIGH);
      delay(50);
      digitalWrite(LED_BUILTIN, LOW);
    }
    else
    {
      Serial.println("\xE2\x9A\xA0\xEF\xB8\x8F Debounced: hit ignored.");
    }
  }
  lastState = currentState;

  handleUsbInput();
  handleLoRa();

  // --- INITIAL FIX DETECTION ---
  if (!initialReportSent)
  {
    GpsPosition pos = getLocation();
    if (pos.valid)
    {
      initialReportSent = true;

      // print & send your first fix
      Serial.print("Initial GPS fix: ");
      Serial.print(pos.lat, 6);
      Serial.print(", ");
      Serial.print(pos.lon, 6);
      Serial.print(", Alt: ");
      Serial.println(pos.alt, 1);

      sendPositionMessage(targetID, pos.lat, pos.lon, pos.alt);
    }
  }
}
// ——————————————————————————
//  getLocation(): one pass through GPS + smoothing
// ——————————————————————————
GpsPosition getLocation()
{
  // 1) read any waiting GPS chars (up to 150 ms)
  unsigned long start = millis();
  while (Serial1.available() && millis() - start < 150)
  {
    gps.encode(Serial1.read());
  }
  // 2) give it one more second for a fresh fix
  unsigned long deadline = millis() + 1000;
  while (millis() < deadline)
  {
    while (Serial1.available())
    {
      gps.encode(Serial1.read());
    }
  }

  GpsPosition pos;
  if (gps.location.isValid() && gps.location.age() < 5000)
  {
    pos.valid = true;
    double rawLat = gps.location.lat();
    double rawLon = gps.location.lng();
    double rawAlt = (gps.altitude.isValid() && gps.altitude.age() < 5000)
                        ? gps.altitude.meters()
                        : 0.0;

    // Seed the smoother on first good reading
    if (!hasSmoothed)
    {
      smoothedLat = rawLat;
      smoothedLon = rawLon;
      smoothedAlt = rawAlt;
      hasSmoothed = true;
    }
    else
    {
      // EMA smoothing
      smoothedLat = SMOOTH_ALPHA * rawLat + (1 - SMOOTH_ALPHA) * smoothedLat;
      smoothedLon = SMOOTH_ALPHA * rawLon + (1 - SMOOTH_ALPHA) * smoothedLon;
      smoothedAlt = SMOOTH_ALPHA * rawAlt + (1 - SMOOTH_ALPHA) * smoothedAlt;
    }

    pos.lat = smoothedLat;
    pos.lon = smoothedLon;
    pos.alt = smoothedAlt;
  }
  else
  {
    pos.valid = false;
    pos.lat = pos.lon = pos.alt = 0.0;
  }
  return pos;
}

void handleLoRa()
{
  int packetSize = LoRa.parsePacket();
  if (packetSize > 0)
  {
    // Serial.print("Received LoRa packet. Size: "); Serial.println(packetSize); // Less verbose
    uint8_t raw[MAX_MSG_LEN];
    int len = 0;
    while (LoRa.available() && len < MAX_MSG_LEN - 1)
    {
      raw[len++] = (uint8_t)LoRa.read();
    }
    raw[len] = '\0';
    // Serial.print("Raw LoRa Data: "); Serial.println((char*)raw); // Less verbose

    char extracted[MAX_MSG_LEN] = {0};
    if (extractFramedMessage(raw, len, extracted))
    {
      // Serial.print("Extracted Framed Msg: "); Serial.println(extracted); // Less verbose
      processMessage(extracted);
    }
    else
    {
      Serial.println("ERROR: No valid frame found in LoRa packet.");
    }
  }
}

void handleUsbInput()
{
  static char usbBuffer[MAX_MSG_LEN] = {0};
  static uint8_t idx = 0;
  static unsigned long lastByteTime = 0;

  while (Serial.available())
  {
    char c = Serial.read();
    lastByteTime = millis();

    if (c == '\n' || c == '\r')
    {
      if (idx > 0)
      {
        usbBuffer[idx] = '\0';
        Serial.print("USB Command Received: ");
        Serial.println(usbBuffer);
        processMessage(usbBuffer);
        idx = 0;
        usbBuffer[0] = '\0';
      }
      continue;
    }
    if (idx < MAX_MSG_LEN - 1)
    {
      usbBuffer[idx++] = c;
    }
    else
    {
      Serial.println("Warning: USB input buffer overflow!");
    }
  }

  if (idx > 0 && millis() - lastByteTime > 1000)
  {
    usbBuffer[idx] = '\0';
    Serial.print("USB Command Received (Timeout): ");
    Serial.println(usbBuffer);
    processMessage(usbBuffer);
    idx = 0;
    usbBuffer[0] = '\0';
  }
}

void processMessage(char *msg)
{
  size_t len = strlen(msg);
  if (len < 3)
  {
    // Serial.println("ERROR: Message too short."); // Less verbose
    return;
  }
  if ((uint8_t)msg[0] != START_MARKER || (uint8_t)msg[len - 1] != END_MARKER)
  {
    Serial.println("ERROR: Invalid message framing.");
    return;
  }

  char csv[MAX_CSV_LEN];
  strncpy(csv, msg + 1, len - 2);
  csv[len - 2] = '\0';

  // Serial.print("Processing Command CSV: "); Serial.println(csv); // Less verbose

  char *command = strtok(csv, ",");
  char *param = strtok(NULL, ",");

  if (!command)
  {
    Serial.println("ERROR: Empty command.");
    return;
  }

  if (strcmp(command, "QUERY") == 0)
  {
    Serial.println("QUERY received.");
    GpsPosition pos = getLocation();
    if (pos.valid)
    {
      Serial.println("GPS is valid. Sending POS message with coordinates.");
      sendPositionMessage(targetID, pos.lat, pos.lon, pos.alt);
    }
    else
    {
      Serial.println("GPS not valid or too old. Sending zeros.");
      sendPositionMessage(targetID, 0.0, 0.0, 0.0);
    }
  }
  // ----------------------------------------
  else if (strcmp(command, "CAL") == 0)
  {
    Serial.print("CAL received");
    if (param)
    {
      Serial.print(" for ID: ");
      Serial.println(param);
      sendCalibrationAck(param);
    }
    else
    {
      Serial.println(" (ERROR: Missing ID parameter)");
    }
  }
  else if (strcmp(command, "CONFIG") == 0)
  {
    Serial.print("CONFIG received");
    if (param)
    {
      if (strlen(param) > 0 && strlen(param) < MAX_ID_LEN)
      {
        Serial.print(". Setting ID to: ");
        Serial.println(param);
        strncpy(targetIDBuf, param, MAX_ID_LEN - 1);
        targetIDBuf[MAX_ID_LEN - 1] = '\0';

        TargetIDWrapper updated;
        strncpy(updated.id, targetIDBuf, MAX_ID_LEN);
        targetStorage.write(updated);

        sendLoraMessage("IDCONFIRM");
        Serial.println("ID updated and saved.");
      }
      else
      {
        Serial.println(" (ERROR: Invalid ID length)");
      }
    }
    else
    {
      Serial.println(" (ERROR: Missing ID parameter)");
    }
  }
  else
  {
    Serial.print("Unknown command received: ");
    Serial.println(command);
  }
}

// Sends Position message: <POS,ID,LAT,LON,ALT,BATT>
// LAT, LON, ALT will be 0.0 if GPS is invalid when called from processMessage
void sendPositionMessage(const char *id, double lat, double lon, double alt)
{
  float battery = readBatteryVoltage();

  // Get GPS quality information
  int satellites = 0;
  double hdop = 99.9;
  String altRef = "UNK"; // Unknown altitude reference

  if (gps.satellites.isValid() && gps.satellites.age() < 5000)
  {
    satellites = gps.satellites.value();
  }

  if (gps.hdop.isValid() && gps.hdop.age() < 5000)
  {
    hdop = gps.hdop.hdop();
  }

  // Determine altitude reference (HAE vs MSL)
  // Most GPS modules provide HAE (Height Above Ellipsoid) by default
  // For accurate ballistics, we need to note this
  if (gps.altitude.isValid() && gps.altitude.age() < 5000)
  {
    altRef = "HAE"; // Height Above Ellipsoid (typical GPS output)
    // Note: Some modules may provide MSL, but HAE is more common
  }

  // Send enhanced position message with GPS quality
  char csv[MAX_CSV_LEN];
  snprintf(csv, sizeof(csv), "POSQ,%s,%.6f,%.6f,%.1f,%.2f,%d,%.1f,%s",
           id, lat, lon, alt, battery, satellites, hdop, altRef.c_str());
  sendLoraMessage(csv);

  // Also send the legacy format for compatibility
  char csvLegacy[MAX_CSV_LEN];
  snprintf(csvLegacy, sizeof(csvLegacy), "POS,%s,%.6f,%.6f,%.1f,%.2f",
           id, lat, lon, alt, battery);
  sendLoraMessage(csvLegacy);
}

// Sends Calibration Acknowledgment: <CALACK,ID>
void sendCalibrationAck(const char *id)
{
  char csv[MAX_CSV_LEN];
  snprintf(csv, sizeof(csv), "CALACK,%s", id);
  sendLoraMessage(csv);
}

// Sends Hit message: <HIT,ID>
void sendHitMessage(const char *id)
{
  char csv[MAX_CSV_LEN];
  snprintf(csv, sizeof(csv), "HIT,%s", id);
  sendLoraMessage(csv);
}

// Sends Shot Fired message: <SHOT,ID,timestamp>
void sendShotFiredMessage(const char *id)
{
  unsigned long timestamp = millis();
  char csv[MAX_CSV_LEN];
  snprintf(csv, sizeof(csv), "SHOT,%s,%lu", id, timestamp);
  sendLoraMessage(csv);
  Serial.print("Shot fired message sent: ");
  Serial.println(csv);
}

// Core function to frame and send a LoRa message
void sendLoraMessage(const char *csv)
{
  char msg[MAX_MSG_LEN];
  size_t len = snprintf(msg, sizeof(msg), "%c%s%c", START_MARKER, csv, END_MARKER);

  if (len >= sizeof(msg))
  {
    Serial.println("ERROR: Formatted message too long to send!");
    len = sizeof(msg) - 1;
    msg[len] = END_MARKER; // Ensure it still ends correctly if truncated
  }

  Serial.print("Sending LoRa message: ");
  Serial.println(msg);

  if (LoRa.beginPacket())
  {
    LoRa.write((uint8_t *)msg, len);
    LoRa.endPacket();
  }
  else
  {
    Serial.println("ERROR: LoRa.beginPacket() failed.");
  }
}

// Extracts the first valid framed message <...> from a buffer
bool extractFramedMessage(uint8_t *buf, int len, char *out)
{
  int startIdx = -1;
  for (int i = 0; i < len; i++)
  {
    if (buf[i] == START_MARKER)
    {
      startIdx = i;
      for (int j = i + 1; j < len; j++)
      {
        if (buf[j] == END_MARKER)
        {
          int msgLen = j - startIdx + 1;
          if (msgLen < MAX_MSG_LEN)
          {
            memcpy(out, &buf[startIdx], msgLen);
            out[msgLen] = '\0';
            return true;
          }
          else
          {
            Serial.println("Warning: Found framed message, but it's too long for buffer.");
            startIdx = -1;
            break;
          }
        }
      }
      if (startIdx != -1)
      {
        startIdx = -1;
      }
    }
  }
  out[0] = '\0';
  return false;
}

// Reads battery voltage
float readBatteryVoltage()
{
  int raw = analogRead(BATTERY_PIN);
  // Adjust calculation based on your voltage divider and board's reference voltage
  float measuredVoltage = raw * (3.3 / 1023.0); // Assuming 3.3V reference
  float batteryVoltage = measuredVoltage * 2.0; // Assuming 1:1 voltage divider (Vin * 0.5 = Vmeasured)
  return batteryVoltage;
}

static void smartDelay(unsigned long ms)
{
  unsigned long start = millis();
  do
  {
    while (Serial1.available())
      gps.encode(Serial1.read());
  } while (millis() - start < ms);
}
