# Minimal GPS Logger

A lightweight Android application written in Java. It connects to an external GPS receiver via Bluetooth Classic (SPP/RFCOMM) and logs the raw NMEA data stream to local files.

## Why this app?

Built for users who need a reliable way to capture raw NMEA data from high-precision external GPS modules without the overhead of complex navigation apps. It focuses on stability, low resource usage, and data integrity.

## Key Features

- **Direct Bluetooth Logging:** Connects to any Bluetooth Classic GPS module using SPP.
- **Raw Data Integrity:** Saves the exact NMEA stream as received.
- **Background Operation:** Runs as a Foreground Service with high-priority alerts.
- **GPX Export:** Easily convert your raw NMEA logs to GPX files. Exported files are automatically saved to your system **Downloads** folder with a notification.
- **Panic System:** Sounds an alarm and vibrates if GPS data stops flowing for more than 30 seconds.
- **Visual Status:** Color-coded notification icons (Green for standby, Red for recording).
- **External Map Integration:** Quickly view your current location in your favorite map app.

## Project Technical Profile

- **Language:** Java 17.
- **Compatibility:** Works on Android 5.0 (API 21) and newer (perfect for older devices like Samsung S4).
- **UI:** Pure native Android `Activity` and XML layouts (no heavy frameworks).
- **Storage:** Logs are stored in the app-specific directory for easy access via USB.
- **Efficiency:** Low memory footprint, optimized data path.

## How to use

1. **Pair:** Pair your GPS receiver with your phone in Android Settings.
2. **Setup:** Open Minimal GPS Logger and grant Bluetooth permissions.
3. **Select:** Choose your paired GPS device from the dropdown menu.
4. **Log:** Click **START**. The icon in the status bar will turn red.
5. **Monitor:** View real-time stats like position, speed, satellites, and file size.
6. **Export:** Click **STOP**, then use **EXPORT GPX** to create a map-ready file.

## Output Files

Logs are stored in:
`Android/data/com.github.mikoreg.gpslogger/files/tracks/`

Files are named: `gps_YYYYMMDD_HHMMSS_part001.nmea` (and `.gpx` after export).

## For Developers

### Building
Requires Android Studio or Gradle.
```bash
./gradlew assembleDebug
```

### Core Architecture
- `MainActivity`: UI handling and permissions.
- `GpsLoggerService`: Manages the background logging lifecycle and notifications.
- `NmeaLoggerEngine`: The heart of the app; handles the Bluetooth connection and data loop.
- `NmeaFileLogger`: Efficiently writes raw data to storage with auto-rotation.
- `NmeaToGpx`: Converts raw NMEA files to the standard GPX format.
- `NmeaParser`: Extract stats (lat, lon, etc.) from the stream for display.

## License
MIT
