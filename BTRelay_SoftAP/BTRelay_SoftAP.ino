#include <WiFi.h>      // Required for Wi-Fi functionality
#include <WebServer.h> // Required for the basic web server
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- Configuration ---
const char *ssid = "ESP32_HitIndicatorAP"; // Name of the Wi-Fi network the ESP32 will create
const char *password = "password123";      // Password for the Wi-Fi network (must be >= 8 chars)
WebServer server(80);                      // Create a web server object on port 80 (standard HTTP)

HardwareSerial FeatherSerial(1); // UART1 for Feather connection

// BLE Configuration
#define BLE_DEVICE_NAME "HitIndicator_BLE" // Name of the BLE device
#define SERVICE_UUID "12345678-1234-1234-1234-123456789abc"
#define POSITION_CHAR_UUID "12345678-1234-1234-1234-123456789abd"
#define HIT_CHAR_UUID "12345678-1234-1234-1234-123456789abe"
#define BATTERY_CHAR_UUID "12345678-1234-1234-1234-123456789abf"
#define CALIBRATION_CHAR_UUID "12345678-1234-1234-1234-123456789ac0"

BLEServer *pServer = nullptr;
BLEService *pService = nullptr;
BLECharacteristic *pPositionCharacteristic = nullptr;
BLECharacteristic *pHitCharacteristic = nullptr;
BLECharacteristic *pBatteryCharacteristic = nullptr;
BLECharacteristic *pCalibrationCharacteristic = nullptr;

bool deviceConnected = false;
bool oldDeviceConnected = false;
unsigned long lastAdvertisingCheck = 0;
const unsigned long ADVERTISING_CHECK_INTERVAL = 30000; // Check every 30 seconds

#define LED_PIN 2

// Function declarations
void blinkLED();
void blinkStartup();
void setupBLE();
void restartBLEAdvertising();
void handleRoot();
void handleNotFound();
void handleBLEStatus();
void handleRestartBLE();

// --- HTML Content for the Welcome Page ---
// Using raw literal string R"rawliteral(...)rawliteral" makes it easy to write multi-line HTML
const char *welcomePageHTML = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
    <title>ESP32 Welcome</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body { font-family: sans-serif; text-align: center; padding: 20px; background-color: #f4f4f4; }
        h1 { color: #007bff; }
        p { color: #333; }
        .container { background-color: #fff; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); display: inline-block; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Welcome to the ESP32 Hit Indicator Base!</h1>
        <p>This device acts as a BLE (Bluetooth Low Energy) bridge and Wi-Fi Access Point.</p>
        <p>Connect via BLE to 'HitIndicator_BLE' or to this Wi-Fi network ('ESP32_HitIndicatorAP').</p>
        <p>IP Address: %IP_ADDRESS%</p>
        <p>BLE Status: %BLE_STATUS%</p>
        <p><a href="/ble" style="color: #007bff;">Check BLE Status</a> | <a href="/restart-ble" style="color: #007bff;">Restart BLE</a></p> </div>
</body>
</html>
)rawliteral";

// --- Helper Functions ---
void blinkLED()
{
    digitalWrite(LED_PIN, HIGH);
    delay(10); // Short blink for data transfer
    digitalWrite(LED_PIN, LOW);
}

void blinkStartup()
{
    for (int i = 0; i < 3; i++)
    {
        digitalWrite(LED_PIN, HIGH);
        delay(200);
        digitalWrite(LED_PIN, LOW);
        delay(200);
    }
}

// --- BLE Server Callbacks ---
class MyServerCallbacks : public BLEServerCallbacks
{
    void onConnect(BLEServer *pServer)
    {
        deviceConnected = true;
        Serial.println("BLE Client connected");
        blinkLED();
    };

    void onDisconnect(BLEServer *pServer)
    {
        deviceConnected = false;
        Serial.println("BLE Client disconnected");
        blinkLED();
    }
};

// --- BLE Characteristic Callbacks ---
class MyCharacteristicCallbacks : public BLECharacteristicCallbacks
{
    void onWrite(BLECharacteristic *pCharacteristic)
    {
        String value = pCharacteristic->getValue().c_str();

        if (value.length() > 0)
        {
            Serial.print("BLE -> Feather: ");

            // Forward BLE data to Feather via Serial
            for (int i = 0; i < value.length(); i++)
            {
                FeatherSerial.write(value[i]);
                Serial.print(value[i]);
            }
            Serial.println();
            blinkLED();
        }
    }
};

// --- Web Server Handler Functions ---
void handleRoot()
{
    // Replace placeholder with actual IP address and BLE status
    String html = String(welcomePageHTML);
    html.replace("%IP_ADDRESS%", WiFi.softAPIP().toString());
    html.replace("%BLE_STATUS%", deviceConnected ? "Connected" : "Advertising");
    server.send(200, "text/html", html); // Send the HTML page with 200 OK status
}

void handleNotFound()
{
    server.send(404, "text/plain", "404: Not Found"); // Send 404 status if page doesn't exist
}

void handleBLEStatus()
{
    String status = "BLE Device: " + String(BLE_DEVICE_NAME) + "\n";
    status += "Service UUID: " + String(SERVICE_UUID) + "\n";
    status += "Connection Status: " + String(deviceConnected ? "Connected" : "Advertising") + "\n";
    status += "Uptime: " + String(millis() / 1000) + " seconds\n";

    server.send(200, "text/plain", status);
}

void handleRestartBLE()
{
    restartBLEAdvertising();
    server.send(200, "text/plain", "BLE advertising restarted successfully");
}

// --- Setup Function ---
void setup()
{
    Serial.begin(115200);                            // USB serial for debug
    FeatherSerial.begin(115200, SERIAL_8N1, 17, 16); // RX = 17, TX = 16

    pinMode(LED_PIN, OUTPUT);
    blinkStartup(); // Blink LED on startup

    Serial.println();
    Serial.println("Configuring Access Point...");

    // Start Wi-Fi in Access Point mode
    WiFi.softAP(ssid, password);

    IPAddress apIP = WiFi.softAPIP(); // Get the IP address of the Access Point
    Serial.print("AP IP address: ");
    Serial.println(apIP);

    // Configure Web Server routes
    server.on("/", HTTP_GET, handleRoot);                  // When client requests root "/", call handleRoot
    server.on("/ble", HTTP_GET, handleBLEStatus);          // BLE status endpoint
    server.on("/restart-ble", HTTP_GET, handleRestartBLE); // Restart BLE endpoint
    server.onNotFound(handleNotFound);                     // When client requests non-existent page, call handleNotFound

    // Start the Web Server
    server.begin();
    Serial.println("HTTP server started");

    // Initialize BLE
    setupBLE();

    Serial.println("ESP32 bridge, AP, Web Server, and BLE ready.");
}

// --- BLE Setup Function ---
void setupBLE()
{
    Serial.println("Initializing BLE...");

    // Initialize BLE device
    BLEDevice::init(BLE_DEVICE_NAME);

    // Create BLE Server
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    // Create BLE Service
    pService = pServer->createService(SERVICE_UUID);

    // Create Position Characteristic
    pPositionCharacteristic = pService->createCharacteristic(
        POSITION_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
            BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_NOTIFY);
    pPositionCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pPositionCharacteristic->addDescriptor(new BLE2902());

    // Create Hit Characteristic
    pHitCharacteristic = pService->createCharacteristic(
        HIT_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
            BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_NOTIFY);
    pHitCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pHitCharacteristic->addDescriptor(new BLE2902());

    // Create Battery Characteristic
    pBatteryCharacteristic = pService->createCharacteristic(
        BATTERY_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
            BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_NOTIFY);
    pBatteryCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pBatteryCharacteristic->addDescriptor(new BLE2902());

    // Create Calibration Characteristic
    pCalibrationCharacteristic = pService->createCharacteristic(
        CALIBRATION_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
            BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_NOTIFY);
    pCalibrationCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pCalibrationCharacteristic->addDescriptor(new BLE2902());

    // Start the service
    pService->start();

    // Start advertising
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);

    // Set advertising parameters for better discoverability
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); // Recommended advertising interval: 20ms
    pAdvertising->setMaxPreferred(0x12); // Recommended advertising interval: 30ms

    // Add device name to advertising data
    BLEAdvertisementData advertisementData;
    advertisementData.setName(BLE_DEVICE_NAME);
    advertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
    advertisementData.setAppearance(0x0000); // Generic device
    pAdvertising->setAdvertisementData(advertisementData);

    // Set scan response data
    BLEAdvertisementData scanResponseData;
    scanResponseData.setShortName("HIT");
    pAdvertising->setScanResponseData(scanResponseData);

    BLEDevice::startAdvertising();

    Serial.println("BLE service started, advertising as: " + String(BLE_DEVICE_NAME));
    Serial.println("Service UUID: " + String(SERVICE_UUID));
    Serial.println("BLE advertising started with optimized parameters");
}

// --- Restart BLE Advertising Function ---
void restartBLEAdvertising()
{
    Serial.println("Restarting BLE advertising...");

    // Stop current advertising
    BLEDevice::stopAdvertising();
    delay(100);

    // Restart advertising with same parameters
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->start();

    Serial.println("BLE advertising restarted");
}

// --- Main Loop ---
void loop()
{
    // Handle incoming web server client requests
    server.handleClient();

    // Periodic advertising status check
    unsigned long currentTime = millis();
    if (currentTime - lastAdvertisingCheck >= ADVERTISING_CHECK_INTERVAL)
    {
        lastAdvertisingCheck = currentTime;
        if (!deviceConnected)
        {
            Serial.println("BLE Status: Advertising (no connections)");
            Serial.println("Device name: " + String(BLE_DEVICE_NAME));
            Serial.println("Service UUID: " + String(SERVICE_UUID));
        }
        else
        {
            Serial.println("BLE Status: Connected to client");
        }
    }

    // Handle BLE connection state changes
    if (!deviceConnected && oldDeviceConnected)
    {
        delay(500);                  // give the bluetooth stack the chance to get things ready
        pServer->startAdvertising(); // restart advertising
        Serial.println("Client disconnected - restarting BLE advertising");
        oldDeviceConnected = deviceConnected;
    }

    // Connecting
    if (deviceConnected && !oldDeviceConnected)
    {
        oldDeviceConnected = deviceConnected;
    }

    // Forward from Feather (via Serial) -> ATAK (via BLE)
    if (FeatherSerial.available() && deviceConnected)
    {
        String message = "";
        while (FeatherSerial.available())
        {
            char c = FeatherSerial.read();
            message += c;
            delay(1); // Small delay to allow buffer to fill
        }

        if (message.length() > 0)
        {
            Serial.print("Feather -> BLE: ");
            Serial.println(message);

            // Route message to appropriate characteristic based on content
            if (message.indexOf("POS") >= 0)
            {
                pPositionCharacteristic->setValue(message.c_str());
                pPositionCharacteristic->notify();
            }
            else if (message.indexOf("HIT") >= 0)
            {
                pHitCharacteristic->setValue(message.c_str());
                pHitCharacteristic->notify();
            }
            else if (message.indexOf("BAT") >= 0 || message.indexOf("V:") >= 0)
            {
                pBatteryCharacteristic->setValue(message.c_str());
                pBatteryCharacteristic->notify();
            }
            else if (message.indexOf("CAL") >= 0)
            {
                pCalibrationCharacteristic->setValue(message.c_str());
                pCalibrationCharacteristic->notify();
            }
            else
            {
                // Default to position characteristic for unknown messages
                pPositionCharacteristic->setValue(message.c_str());
                pPositionCharacteristic->notify();
            }
            blinkLED();
        }
    }
}