# Android Virtual Printer

An Android application that simulates an IPP (Internet Printing Protocol) printer for testing and quality assurance purposes. This virtual printer can capture print jobs from any network device and provides a user interface to inspect and manage received documents.

## Features

- **IPP 2.0 Compliance**: Full support for standard IPP operations including Print-Job, Get-Printer-Attributes, Create-Job, and Send-Document
- **Network Discovery**: Automatic printer advertisement via DNS-SD (mDNS/Bonjour)
- **Document Processing**: Handles multiple document formats (PDF, PostScript, images) with automatic format detection
- **Custom Attributes**: Support for loading and using custom IPP attribute configurations
- **Plugin System**: Extensible architecture for custom processing workflows
- **Modern UI**: Built with Jetpack Compose for a responsive user experience

## Architecture

This application follows Clean Architecture principles with clear separation of concerns:

- **Domain Layer**: Business logic and entities
- **Data Layer**: Repositories and data sources  
- **Presentation Layer**: UI components and ViewModels
- **Core Layer**: Shared utilities and configurations

For detailed architecture information, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Setup and Installation

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK API 29+ (Android 10+)
- Kotlin 1.9.22+

### Building the App

1. Clone the repository
2. Open the project in Android Studio
3. Sync the project with Gradle files
4. Build and run on an Android device or emulator

```bash
git clone <repository-url>
cd Printer
./gradlew assembleDebug
```

## Usage

### Starting the Virtual Printer

1. Launch the Android Virtual Printer app
2. Navigate to the "Printer Service" section
3. Tap "Start Printer Service" to begin advertising the virtual printer on the network
4. The printer will be discoverable as "Android Virtual Printer" on port 8631

### Testing Print Jobs

#### From Windows
```bash
# Add printer using Windows Add Printer wizard
# Use IPP URL: http://[android-device-ip]:8631/ipp/print
```

#### From macOS
```bash
# Add printer in System Preferences > Printers & Scanners
# Use IPP URL: ipp://[android-device-ip]:8631/ipp/print
```

#### From Linux/CUPS
```bash
# Add printer using CUPS web interface or command line
lpadmin -p AndroidVirtualPrinter -E -v ipp://[android-device-ip]:8631/ipp/print -m everywhere
```

#### Testing with Command Line (curl)
```bash
# Test Get-Printer-Attributes
curl -X POST -H "Content-Type: application/ipp" \
  --data-binary @get-printer-attributes.ipp \
  http://[android-device-ip]:8631/ipp/print

# Test with a PDF document
curl -X POST -H "Content-Type: application/ipp" \
  --data-binary @print-job-request.ipp \
  http://[android-device-ip]:8631/ipp/print
```

### Custom Attributes

The app supports loading custom IPP attribute configurations:

1. Go to Settings > IPP Attributes
2. Use "Import Attributes" to load a JSON file with custom printer capabilities
3. Supports both legacy array format and modern printer response format

#### Example Custom Attributes JSON
```json
{
  "response": {
    "operation-attributes": {
      "attributes-charset": {"type": "charset", "value": "utf-8"},
      "attributes-natural-language": {"type": "naturalLanguage", "value": "en"}
    },
    "printer-attributes": {
      "printer-name": {"type": "name", "value": "Custom Virtual Printer"},
      "printer-state": {"type": "enum", "value": 3},
      "printer-is-accepting-jobs": {"type": "boolean", "value": true},
      "document-format-supported": {
        "type": "mimeMediaType", 
        "value": ["application/pdf", "image/jpeg", "text/plain"]
      }
    }
  }
}
```

### Viewing Print Jobs

Received print jobs are automatically saved to the app's internal storage and can be viewed in the "Print Jobs" section:

- **Job Details**: View metadata like job ID, submission time, document format, and size
- **Document Preview**: Open saved documents using the device's default applications
- **Export**: Share or export captured documents

## Development

### Testing the Four Core Goals

#### 1. ✅ License (Apache 2.0)
- LICENSE file has been added to the project root
- All source files include proper copyright headers

#### 2. ✅ Printer Discovery  
- DNS-SD service advertisement working
- Printer discoverable on network via mDNS
- Self-discovery capabilities included
- Test: Use network scanner or printer discovery tools

#### 3. ✅ Print Job Processing
- IPP Print-Job and Send-Document operations implemented
- Document extraction and format detection working
- Job queue management with plugin hooks
- File storage with organized hierarchy
- Test: Send print jobs from various clients (Windows, macOS, Linux)

#### 4. ✅ Custom Attributes Upload
- JSON parsing supports both legacy and modern formats
- Robust error handling and validation
- Multi-value attribute support
- Custom attribute validation with flexible strictness levels
- Test: Import various JSON attribute files

### Running Tests

```bash
# Unit tests
./gradlew test

# Android instrumentation tests  
./gradlew connectedAndroidTest

# Lint checks
./gradlew lint
```

### IPP Protocol Testing

For comprehensive IPP testing, you can use tools like:

- **ipptool**: Command-line IPP testing tool from CUPS
- **Wireshark**: Network packet analysis for IPP traffic inspection
- **Printer Emulation**: Test with various printer driver configurations

Example ipptool test:
```bash
ipptool -tv ipp://[android-device-ip]:8631/ipp/print get-printer-attributes.test
```

## Troubleshooting

### Common Issues

1. **Printer not discoverable**: 
   - Check that both devices are on the same network
   - Verify port 8631 is not blocked by firewall
   - Ensure mDNS/Bonjour is enabled on the client device

2. **Print jobs not received**:
   - Check Android app logs for IPP request details
   - Verify document format is supported
   - Ensure sufficient storage space on device

3. **Custom attributes not loading**:
   - Validate JSON format using online JSON validator
   - Check app logs for parsing error details
   - Ensure required attribute groups are present

### Debug Logging

Enable verbose logging by setting the log level in the app:
- Go to Settings > Developer Options > Logging Level
- Set to "Debug" or "Verbose" for detailed IPP transaction logs

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Technology Stack

- **Kotlin** - Primary language
- **Jetpack Compose** - Modern UI toolkit
- **Ktor** - Embedded HTTP/IPP server
- **HP JIPP** - Java IPP protocol library
- **Coroutines** - Asynchronous programming
- **Android Architecture Components** - MVVM and lifecycle management

## Related Projects

- [IPP Everywhere](https://www.pwg.org/ipp/everywhere.html) - IPP standard specification
- [CUPS](https://www.cups.org/) - Common UNIX Printing System
- [HP JIPP](https://github.com/HPInc/jipp) - Java IPP implementation

---

For technical details and development guidelines, see [ARCHITECTURE.md](ARCHITECTURE.md).

This is not an officially supported Google product. This project is not
eligible for the [Google Open Source Software Vulnerability Rewards
Program](https://bughunters.google.com/open-source-security).
