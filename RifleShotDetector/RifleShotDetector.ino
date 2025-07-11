#include <SPI.h>
#include <LoRa.h>

// LoRa radio pins (adjust for your board)
#define RFM95_CS 8
#define RFM95_INT 3
#define RFM95_RST 4
#define LORA_FREQUENCY 915E6

// Shot detection configuration
#define MICROPHONE_PIN A0      // Analog pin for microphone
#define ACCELEROMETER_X_PIN A1 // Accelerometer X axis
#define ACCELEROMETER_Y_PIN A2 // Accelerometer Y axis
#define ACCELEROMETER_Z_PIN A3 // Accelerometer Z axis

// Thresholds for shot detection
#define AUDIO_THRESHOLD 512    // Audio level threshold for muzzle blast
#define ACCEL_THRESHOLD 100    // Acceleration threshold for recoil
#define DETECTION_WINDOW_MS 50 // Time window to correlate audio and acceleration
#define DEBOUNCE_MS 1000       // Minimum time between shots

// LED and status
#define LED_PIN 13
#define STATUS_LED_PIN 12

// Shot detection state
unsigned long lastShotTime = 0;
bool shotDetected = false;

// Calibration values (set during setup)
int audioBaseline = 0;
int accelXBaseline = 0;
int accelYBaseline = 0;
int accelZBaseline = 0;

// Target ID to send shot notifications to
String targetID = "ALL"; // Send to all targets, or specify specific target

void setup()
{
  Serial.begin(9600);

  // Initialize pins
  pinMode(LED_PIN, OUTPUT);
  pinMode(STATUS_LED_PIN, OUTPUT);
  pinMode(MICROPHONE_PIN, INPUT);
  pinMode(ACCELEROMETER_X_PIN, INPUT);
  pinMode(ACCELEROMETER_Y_PIN, INPUT);
  pinMode(ACCELEROMETER_Z_PIN, INPUT);

  delay(2000);
  Serial.println("Rifle Shot Detector Starting...");

  // Initialize LoRa
  LoRa.setPins(RFM95_CS, RFM95_RST, RFM95_INT);
  if (!LoRa.begin(LORA_FREQUENCY))
  {
    Serial.println("LoRa init failed. Check wiring.");
    while (true)
    {
      digitalWrite(LED_PIN, HIGH);
      delay(100);
      digitalWrite(LED_PIN, LOW);
      delay(100);
    }
  }

  Serial.println("LoRa initialized successfully.");
  digitalWrite(STATUS_LED_PIN, HIGH);

  // Calibrate sensors
  calibrateSensors();

  Serial.println("Shot detector ready!");
  Serial.println("Commands:");
  Serial.println("  CAL - Recalibrate sensors");
  Serial.println("  TARGET <ID> - Set target ID for notifications");
  Serial.println("  TEST - Send test shot message");

  blinkReady();
}

void loop()
{
  handleSerialCommands();
  detectShot();
  delay(1); // Small delay to prevent overwhelming the processor
}

void calibrateSensors()
{
  Serial.println("Calibrating sensors... Keep rifle steady for 3 seconds...");
  digitalWrite(LED_PIN, HIGH);

  long audioSum = 0;
  long accelXSum = 0;
  long accelYSum = 0;
  long accelZSum = 0;

  int samples = 1000;
  for (int i = 0; i < samples; i++)
  {
    audioSum += analogRead(MICROPHONE_PIN);
    accelXSum += analogRead(ACCELEROMETER_X_PIN);
    accelYSum += analogRead(ACCELEROMETER_Y_PIN);
    accelZSum += analogRead(ACCELEROMETER_Z_PIN);
    delay(3);
  }

  audioBaseline = audioSum / samples;
  accelXBaseline = accelXSum / samples;
  accelYBaseline = accelYSum / samples;
  accelZBaseline = accelZSum / samples;

  digitalWrite(LED_PIN, LOW);

  Serial.println("Calibration complete:");
  Serial.print("  Audio baseline: ");
  Serial.println(audioBaseline);
  Serial.print("  Accel X baseline: ");
  Serial.println(accelXBaseline);
  Serial.print("  Accel Y baseline: ");
  Serial.println(accelYBaseline);
  Serial.print("  Accel Z baseline: ");
  Serial.println(accelZBaseline);
}

void detectShot()
{
  unsigned long currentTime = millis();

  // Debounce check
  if (currentTime - lastShotTime < DEBOUNCE_MS)
  {
    return;
  }

  // Read sensor values
  int audioLevel = analogRead(MICROPHONE_PIN);
  int accelX = analogRead(ACCELEROMETER_X_PIN);
  int accelY = analogRead(ACCELEROMETER_Y_PIN);
  int accelZ = analogRead(ACCELEROMETER_Z_PIN);

  // Calculate deviations from baseline
  int audioDeviation = abs(audioLevel - audioBaseline);
  int accelXDeviation = abs(accelX - accelXBaseline);
  int accelYDeviation = abs(accelY - accelYBaseline);
  int accelZDeviation = abs(accelZ - accelZBaseline);

  // Calculate combined acceleration deviation
  int accelTotalDeviation = sqrt(
      (accelXDeviation * accelXDeviation) +
      (accelYDeviation * accelYDeviation) +
      (accelZDeviation * accelZDeviation));

  // Check for shot detection
  bool audioTriggered = audioDeviation > AUDIO_THRESHOLD;
  bool accelTriggered = accelTotalDeviation > ACCEL_THRESHOLD;

  if (audioTriggered || accelTriggered)
  {
    // Wait a bit to see if both trigger within the detection window
    unsigned long detectionStart = millis();
    bool bothTriggered = audioTriggered && accelTriggered;

    while (!bothTriggered && (millis() - detectionStart) < DETECTION_WINDOW_MS)
    {
      if (!audioTriggered)
      {
        int newAudio = analogRead(MICROPHONE_PIN);
        audioTriggered = abs(newAudio - audioBaseline) > AUDIO_THRESHOLD;
      }

      if (!accelTriggered)
      {
        int newAccelX = analogRead(ACCELEROMETER_X_PIN);
        int newAccelY = analogRead(ACCELEROMETER_Y_PIN);
        int newAccelZ = analogRead(ACCELEROMETER_Z_PIN);
        int newAccelTotal = sqrt(
            (abs(newAccelX - accelXBaseline) * abs(newAccelX - accelXBaseline)) +
            (abs(newAccelY - accelYBaseline) * abs(newAccelY - accelYBaseline)) +
            (abs(newAccelZ - accelZBaseline) * abs(newAccelZ - accelZBaseline)));
        accelTriggered = newAccelTotal > ACCEL_THRESHOLD;
      }

      bothTriggered = audioTriggered && accelTriggered;
      delay(1);
    }

    if (bothTriggered)
    {
      onShotDetected();
    }
    else
    {
      // Single sensor trigger - might be false positive
      Serial.print("Single sensor trigger - Audio: ");
      Serial.print(audioTriggered ? "YES" : "NO");
      Serial.print(", Accel: ");
      Serial.println(accelTriggered ? "YES" : "NO");
    }
  }
}

void onShotDetected()
{
  lastShotTime = millis();

  Serial.println("🔫 SHOT DETECTED!");
  Serial.print("Timestamp: ");
  Serial.println(lastShotTime);

  // Visual feedback
  digitalWrite(LED_PIN, HIGH);
  delay(100);
  digitalWrite(LED_PIN, LOW);

  // Send shot notification via LoRa
  sendShotNotification();
}

void sendShotNotification()
{
  String message = "<SHOT," + targetID + "," + String(lastShotTime) + ">";

  Serial.print("Sending shot notification: ");
  Serial.println(message);

  LoRa.beginPacket();
  LoRa.print(message);
  LoRa.endPacket();

  // Brief status indication
  digitalWrite(STATUS_LED_PIN, LOW);
  delay(50);
  digitalWrite(STATUS_LED_PIN, HIGH);
}

void handleSerialCommands()
{
  if (Serial.available())
  {
    String command = Serial.readStringUntil('\n');
    command.trim();
    command.toUpperCase();

    if (command == "CAL")
    {
      calibrateSensors();
    }
    else if (command.startsWith("TARGET "))
    {
      String newTargetID = command.substring(7);
      newTargetID.trim();
      if (newTargetID.length() > 0)
      {
        targetID = newTargetID;
        Serial.print("Target ID set to: ");
        Serial.println(targetID);
      }
      else
      {
        Serial.println("Invalid target ID");
      }
    }
    else if (command == "TEST")
    {
      Serial.println("Sending test shot...");
      onShotDetected();
    }
    else if (command == "STATUS")
    {
      printStatus();
    }
    else if (command == "HELP")
    {
      printHelp();
    }
    else
    {
      Serial.print("Unknown command: ");
      Serial.println(command);
      Serial.println("Type HELP for available commands");
    }
  }
}

void printStatus()
{
  Serial.println("=== Shot Detector Status ===");
  Serial.print("Target ID: ");
  Serial.println(targetID);
  Serial.print("Audio baseline: ");
  Serial.println(audioBaseline);
  Serial.print("Audio threshold: ");
  Serial.println(AUDIO_THRESHOLD);
  Serial.print("Accel threshold: ");
  Serial.println(ACCEL_THRESHOLD);
  Serial.print("Last shot: ");
  Serial.println(lastShotTime);

  // Current sensor readings
  int currentAudio = analogRead(MICROPHONE_PIN);
  int currentAccelX = analogRead(ACCELEROMETER_X_PIN);
  int currentAccelY = analogRead(ACCELEROMETER_Y_PIN);
  int currentAccelZ = analogRead(ACCELEROMETER_Z_PIN);

  Serial.print("Current audio: ");
  Serial.print(currentAudio);
  Serial.print(" (dev: ");
  Serial.print(abs(currentAudio - audioBaseline));
  Serial.println(")");
  Serial.print("Current accel: X=");
  Serial.print(currentAccelX);
  Serial.print(" Y=");
  Serial.print(currentAccelY);
  Serial.print(" Z=");
  Serial.println(currentAccelZ);
}

void printHelp()
{
  Serial.println("=== Shot Detector Commands ===");
  Serial.println("CAL - Recalibrate sensors");
  Serial.println("TARGET <ID> - Set target ID for notifications");
  Serial.println("TEST - Send test shot message");
  Serial.println("STATUS - Show current status and sensor readings");
  Serial.println("HELP - Show this help");
}

void blinkReady()
{
  for (int i = 0; i < 3; i++)
  {
    digitalWrite(LED_PIN, HIGH);
    digitalWrite(STATUS_LED_PIN, HIGH);
    delay(200);
    digitalWrite(LED_PIN, LOW);
    digitalWrite(STATUS_LED_PIN, LOW);
    delay(200);
  }
  digitalWrite(STATUS_LED_PIN, HIGH); // Keep status LED on
}
