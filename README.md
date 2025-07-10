# ATAK Hit Indicator Plugin

A tactical training and marksmanship assessment plugin for ATAK (Android Tactical Awareness Kit) that provides real-time hit detection, target tracking, and ballistic analysis capabilities.

_________________________________________________________________
## PURPOSE AND CAPABILITIES

The Hit Indicator Plugin enhances ATAK's tactical capabilities by providing:

### Core Features
- **Real-time Hit Detection**: Tracks hits on BLE-enabled targets with immediate feedback
- **Target Position Tracking**: Monitors and displays target locations via LoRa-to-BLE bridge
- **Ballistic Analysis**: Calculates range, bearing, elevation, and ballistic trajectories
- **Multi-Target Management**: Supports simultaneous tracking of multiple targets
- **Multi-Device Support**: BLE advertising allows multiple phones to receive data simultaneously
- **Training Analytics**: Provides hit statistics, calibration data, and performance metrics

### Advanced Capabilities
- **Elevation Profile Visualization**: Custom 3D elevation charts showing firing line to target
- **MSL Altitude Calculations**: Accurate Mean Sea Level altitude computations
- **Battery Monitoring**: Tracks target device battery levels
- **Calibration System**: Precision timing calibration for accurate hit detection
- **Map Integration**: Seamless integration with ATAK's mapping and coordinate systems

_________________________________________________________________
## STATUS

**Current Version**: 1.0.0  
**Status**: Active Development  
**ATAK Compatibility**: 4.6.0+  
**Release Date**: July 2025  
**Target Users**: Military training units, law enforcement, competitive shooting organizations

**Development Roadmap**:
- ✅ Core hit detection and tracking (Complete)
- ✅ Bluetooth Classic SPP communication (Complete - being migrated to BLE)
- ✅ Basic UI and target management (Complete)
- 🔄 BLE communication implementation (In Progress)
- 🔄 Advanced analytics and reporting (In Progress)
- 📋 Multi-device BLE broadcasting (Planned)
- 📋 Enhanced ATAK integration features (Planned)

_________________________________________________________________
## POINT OF CONTACTS

**Primary Developer**: [Your Name/Organization]  
**Email**: [contact@email.com]  
**Support**: [support@email.com]  
**Repository**: [GitHub/GitLab URL]  

**Technical Support Hours**: Monday-Friday, 0800-1700 EST  
**Emergency Contact**: [emergency contact for critical issues]

_________________________________________________________________
## PORTS REQUIRED

### Network Ports
- **Bluetooth Low Energy (BLE)**: Uses BLE advertising and GATT services for multi-device support
- **LoRa Communication**: Target sensors communicate via LoRa to ESP32 bridge
- **No TCP/UDP ports required** for basic operation

### Future Networking (Planned Features)
- **Port 8080**: HTTP API for web-based configuration (Optional)
- **Port 8443**: HTTPS API for secure web interface (Optional)
- **Port 5555**: UDP for broadcast target discovery (Optional)

### Security Considerations
- BLE communications use standard BLE security features
- LoRa communication is broadcast (consider encryption for sensitive data)
- No external network connections required for core functionality
- Local network access only for optional features

_________________________________________________________________
## EQUIPMENT REQUIRED

### Minimum Requirements
- **Android Device**: Android 7.0+ (API level 24)
- **ATAK Version**: 4.6.0 or higher (Plugin developed for ATAK CIV/MIL versions)
- **ATAK SDK**: Must be compiled against official ATAK SDK (not regular Android SDK)
- **Bluetooth**: Bluetooth Low Energy (BLE) 4.0+ support
- **Memory**: 2GB RAM minimum, 4GB recommended
- **Storage**: 50MB available space

### ATAK Plugin Constraints
- **No MainActivity**: Plugin runs within ATAK's activity context
- **DropDown UI Pattern**: Uses ATAK's dropdown pane system for UI
- **ATAK Permissions**: Inherits permissions from ATAK host application
- **Plugin Lifecycle**: Follows ATAK's MapComponent lifecycle (onCreate/onDestroy)
- **No Launcher Activity**: Plugin is activated through ATAK's plugin system
- **Context Restrictions**: Plugin context differs from standard Android app context

### Target Hardware
- **LoRa-based target sensors** with hit detection capability
- **LoRa-to-BLE bridge**: Adafruit M0 LoRa board with ESP32 for BLE advertising
- **Compatible target systems**:
  - Custom LoRa target sensors with accelerometer/impact detection
  - GPS-enabled target modules for position reporting
  - ESP32-based BLE advertising bridge devices

### Optional Equipment
- **GPS**: For enhanced position accuracy
- **Rangefinder**: For distance verification (manual input)
- **Weather station**: For environmental data integration

_________________________________________________________________
## EQUIPMENT SUPPORTED

### Tested Target Systems
- **LoRa-based target sensors** with ESP32 BLE bridge
- **Adafruit M0 LoRa** with ESP32 for multi-device BLE advertising
- **Custom impact detection sensors** with LoRa communication

### BLE Communication
- **Supported profiles**: BLE Generic Attribute Profile (GATT)
- **Advertising mode**: Allows multiple devices to connect simultaneously
- **Range**: Typical 10-100 meters depending on ESP32 implementation
- **Concurrent connections**: Multiple devices can receive broadcast data
- **Data format**: Custom GATT characteristics for position and hit data

### Android Devices
- **Tablets**: Samsung Galaxy Tab series, Lenovo tablets
- **Phones**: Android devices with ATAK support
- **Rugged devices**: Military-grade Android devices

_________________________________________________________________
## COMPILATION

### Prerequisites
- **Android Studio**: 4.2.2 or higher
- **Java**: JDK 8 or higher
- **ATAK SDK**: Version 4.6.0 (Official ATAK SDK from authorized sources)
- **Gradle**: 7.0+
- **ATAK Plugin Template**: Use official ATAK plugin template structure

### ATAK-Specific Build Requirements
- **Plugin Structure**: Must follow ATAK plugin architecture (MapComponent → DropDownReceiver pattern)
- **Permissions**: BLE permissions must be declared in plugin manifest (inherited by ATAK)
- **Resource Constraints**: Limited to ATAK's resource loading mechanisms
- **Library Dependencies**: Must be compatible with ATAK's existing libraries
- **Build Variants**: Support for CIV/MIL build variants required

### Build Instructions
1. **Clone Repository**:
   ```bash
   git clone [repository-url]
   cd atakHitIndicator
   ```

2. **Configure ATAK SDK**:
   - Copy ATAK SDK to `app/libs/`
   - Update `local.properties` with SDK paths

3. **Build Debug Version**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Build Release Version**:
   ```bash
   ./gradlew assembleCivRelease
   ```

### Build Outputs
- **Debug APK**: `app/build/outputs/apk/debug/`
- **Release APK**: `app/build/outputs/apk/civRelease/`
- **Mapping Files**: `app/build/outputs/mapping/`

### Signing
- Configure signing keys in `keystore.properties`
- Use provided keystore for debug builds
- Generate production keystore for release builds

_________________________________________________________________
## DEVELOPER NOTES

### Architecture Overview
```
HitIndicatorMapComponent (Main Plugin Component)
├── HitIndicatorDropDownReceiver (UI Controller)
├── TargetManager (Target Data Management)
├── BLEManager (BLE Communication Layer) // Updated from BluetoothSerialManager
├── MessageParser (Protocol Handler)
└── ElevationProfileView (Custom Visualization)
```

### Key Classes
- **HitIndicatorMapComponent**: Main plugin registration and ATAK lifecycle management
- **HitIndicatorDropDownReceiver**: UI management using ATAK's DropDown system
- **TargetManager**: Target data persistence and management
- **BLEManager**: BLE communication and GATT service management (replacing BluetoothSerialManager)
- **MessageParser**: Protocol parsing and message handling
- **Target**: Data model for target information

### ATAK Integration Points
- **DropDownMapComponent**: Extends ATAK's dropdown system for UI
- **AtakBroadcast**: Uses ATAK's broadcast system for plugin communication
- **MapView Integration**: Direct integration with ATAK's map system
- **Marker Management**: Uses ATAK's marker system for target display
- **Plugin Registration**: Follows ATAK's plugin registration pattern

### Communication Protocol
```
LoRa Target Sensor → ESP32 Bridge → BLE Advertisement → Android ATAK Plugin

BLE GATT Service Structure:
- Service UUID: Custom service for hit indicator data
- Position Characteristic: Target ID, GPS coordinates, timestamp
- Hit Characteristic: Target ID, hit timestamp, impact data
- Battery Characteristic: Target ID, voltage level
- Calibration Characteristic: Bidirectional calibration data

Message Examples:
- Position: "T001,34.1234,-118.5678,100.5,1625097600,12.3"
- Hit: "T001,1625097655,IMPACT"
- Battery: "T001,3.7V"
```

### Database Schema
- Targets stored in-memory with optional persistence
- SQLite integration available for extended storage
- Parcelable implementation for Android lifecycle management

### Testing
- Unit tests in `app/src/test/`
- Instrumentation tests in `app/src/androidTest/`
- Mock Bluetooth adapter for testing without hardware

### Known Limitations
- **BLE range**: Limited by ESP32 BLE implementation (typically 10-100m)
- **LoRa latency**: Small delay between target hit and BLE advertisement
- **Battery impact**: Continuous BLE scanning affects device battery
- **Data throughput**: BLE has lower throughput than Bluetooth Classic

### Future Enhancements
- Wi-Fi communication support for longer range
- LoRa mesh networking for extended coverage
- Advanced analytics and reporting
- Multi-user/multi-device scenarios with conflict resolution
- Integration with external training systems
- Real-time telemetry visualization

### Troubleshooting
- **Connection issues**: Check BLE permissions and ensure ESP32 is advertising
- **UI problems**: Verify ATAK version compatibility
- **Performance**: Monitor memory usage with large target counts
- **Battery drain**: Implement intelligent scanning intervals
- **LoRa issues**: Check antenna connections and frequency settings

### Contributing
- Follow Android coding standards
- Include unit tests for new features
- Update documentation for API changes
- Test on multiple Android versions and devices
