# Avaruus Astro (Avaruusmulkko Astrophotography Suite)

A dedicated astrophotography capture, sequencing, and processing ecosystem designed specifically for the **Google Pixel 9 Pro XL**.

---

## Hardware Integration (Pixel 9 Pro XL)

The app directly addresses the three flagship rear sensors via Android Camera2 API:
- **0.5x Ultra-Wide**: 48 MP Sony IMX858, ƒ/1.7 aperture, 123° FOV (wide-field Milky Way, aurora, meteors).
- **1.0x Main Wide**: 50 MP Samsung GNK, 1/1.31" sensor, ƒ/1.68 aperture (high-light-throughput deep sky).
- **5.0x Telephoto**: 48 MP Sony IMX858, ƒ/2.8 periscope, 22° FOV (Andromeda M31, Orion M42, Moon, star clusters).

### Key Features
1. **Raw Sensor Capture (`RAW_SENSOR` -> `.dng`)**:
   - Captures uncompressed Bayer RAW frames saved with full total capture metadata via Android's `DngCreator`.
   - Disables computational noise reduction (`NOISE_REDUCTION_MODE_OFF`), hot-pixel removal (`HOT_PIXEL_MODE_OFF`), and optical stabilization (`LENS_OPTICAL_STABILIZATION_MODE_OFF`) to preserve linear starlight data without smearing stars.
2. **Astrophotography Intervalometer**:
   - Continuous timed burst shooting (10, 25, 50, 100, or unlimited frames) for multi-hour deep sky integration.
   - Settle/cooling delays between exposures to reduce sensor thermal noise.
   - `PARTIAL_WAKE_LOCK` and `FLAG_KEEP_SCREEN_ON` ensure long captures aren't killed in the background.
3. **Calibration Sequencing**:
   - **LIGHT**: Normal starry sky frames.
   - **DARK**: OLED screen blanking mode (zero photon leakage) to capture thermal and amp-glow dark frames while the lens is capped.
   - **BIAS**: Shortest hardware shutter speed at target ISO to calibrate sensor readout noise.
   - **FLAT**: Uniform illumination frames to correct lens vignetting.
4. **Night-Vision UI**:
   - Pure black (`#000000`) and deep red (`#FF1744`) interface to preserve scotopic vision (dark adaptation).
   - Digital zoom loupe (1x, 2x, 5x, 10x) for pinpoint star focusing.
   - One-tap Infinity Lock (`∞ LOCK`) and micro-step focus buttons (`±0.05`).

---

## Project Structure

```
d:\Avaruusmulkko\
├── mobile\                 # Native Android application (Kotlin + Jetpack Compose)
│   ├── app\                # Main app module
│   │   └── src\main\java\com\avaruus\astro\
│   │       ├── camera\     # Camera2 manager, sensor enumeration, DNG creator
│   │       ├── sequencer\  # Intervalometer state machine & wake lock
│   │       └── ui\         # Night-vision Compose UI, loupe, & controls
│   ├── build_and_install.ps1 # One-click build & ADB install script
│   └── gradlew.bat         # Gradle build wrapper
├── tether\                 # Windows 11 live tether & DNG ingest tool
│   └── tether_capture.py   # Real-time watcher pulling DNGs from phone to PC
└── data\                   # Local destination for downloaded sessions
```

---

## Quick Start: Build & Install to Pixel 9 Pro XL

### Prerequisites
- Windows 11 machine with USB-C cable.
- Android SDK installed (`$env:LOCALAPPDATA\Android\Sdk`).
- On your Pixel 9 Pro XL:
  1. Open **Settings** -> **About phone** -> Tap **Build number** 7 times to enable Developer Options.
  2. Open **Settings** -> **System** -> **Developer options** -> Enable **USB debugging**.
  3. Connect the phone to your PC and tap **Allow USB debugging** when prompted.

### One-Click Build & Install
Run in PowerShell:
```powershell
cd d:\Avaruusmulkko\mobile
.\build_and_install.ps1
```
The script will:
1. Compile the project using Gradle and OpenJDK.
2. Verify the connected Pixel 9 Pro XL via ADB.
3. Install the APK (`app-debug.apk`) directly to your phone.
4. Launch the app on your screen!

---

## Live PC Tethering (`tether/`)

While shooting on a tripod or star tracker, you can stream RAW frames directly to your Windows 11 PC as they are shot:

```powershell
cd d:\Avaruusmulkko\tether

# Check ADB connection to Pixel 9 Pro XL
python tether_capture.py --check

# Watch and auto-download new DNGs as they are saved
python tether_capture.py --watch

# List sessions on phone
python tether_capture.py --list

# Pull an entire session
python tether_capture.py --pull
```

Downloaded `.dng` files can be directly loaded into Siril, DeepSkyStacker, PixInsight, or GraXpert on your PC.
