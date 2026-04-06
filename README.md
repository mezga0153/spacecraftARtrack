# Spacecraft AR Track

An Android augmented-reality app that overlays the positions of **Artemis II**, the **Moon**, and the **Sun** on your camera feed in real time.

Point your phone at the sky and see exactly where each object is — with crosshair markers, outline circles scaled to angular size, and off-screen edge arrows when targets are outside the field of view.

## Features

- **AR overlay** — real-time projection of celestial objects onto the camera view using device sensors and camera intrinsics
- **Artemis II tracking** — live position from NASA JPL Horizons API (refreshed every 10 s)
- **Moon & Sun** — computed locally using astronomical algorithms (Jean Meeus)
- **Pinch-to-zoom** + lens presets (0.5×, 1×, 2×, 5×, 10×)
- **Heading stability detection** — calibration banner until compass is reliable
- **Compact info card** — target names and distances at a glance

## Tech Stack

- Kotlin 2.3 / Jetpack Compose
- CameraX + Camera2 intrinsics
- Hilt dependency injection
- Rotation vector sensor fusion
- NASA JPL Horizons REST API

## Requirements

- Android 9+ (API 28)
- Camera & location permissions
- Internet access (for Artemis position)

## Building

```bash
cd app
JAVA_HOME="/path/to/jdk" ./gradlew :mobile:assembleDebug
```

## Running on a connected device

```bash
./scripts/run-phone.sh
```

## License

MIT
