# Link Cam

An open source video monitor for Android and iPad, by Linker from Ferret Media.

Plug an HDMI capture dongle into your phone or tablet's USB-C port and get a
clean, low-latency picture of your camera's output, with the tools you'd
find on a field monitor.

> **Status: early.** The capture pipeline and the first tools work against the
> built-in test pattern; capture from a USB device is written but not yet
> verified on hardware. Expect things to change.

## What it does today

- Live view from USB Video Class (UVC) capture devices: MJPEG, YUY2 and NV12,
  decoded once and drawn on the GPU.
- A built-in test pattern (75% bars and a 0–100 IRE ramp) for trying the tools
  without hardware.
- Monitoring tools:
  - False color, with ARRI-style bands.
  - Zebras at a chosen level.
  - Focus peaking, with a choice of colour and sensitivity.
  - Frame lines (2.39, 1.85, 4:5, 9:16, 1:1).
  - Action and title safe areas.
  - 2× punch-in.
- Explicit colour interpretation per source (BT.601/709/2020, full or limited
  range), with a manual override.
- A status readout of format, frame rate, app latency and dropped frames.

## Requirements

- Android 10 or newer with USB host (OTG) support, or an iPad with USB-C on
  iPadOS 17 or newer. iPhone doesn't support USB video devices.
- A UVC capture device. Most cheap USB HDMI capture dongles are UVC.

Both systems only give apps access to USB video devices if they have camera
permission, so Link Cam asks for it. It never opens the device's own cameras.

The iPad app is new. It has the monitor, the tools, the scopes, HDR view and
noise reduction. The camera link (reading the camera's settings over Wi-Fi)
is Android only for now.

## Building

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :shared:jvmTest              # unit tests for the shared rules
./gradlew :android:app:assembleDebug   # app/build/outputs/apk/debug/
```

The native libraries (libusb, libuvc, libjpeg-turbo) are downloaded and built
from source on the first build, pinned by version and checksum.

The iPad app needs a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
ios/generate.sh                        # writes ios/LinkCam.xcodeproj
open ios/LinkCam.xcodeproj             # or: ios/install.sh, to a connected iPad
```

Xcode builds the shared Kotlin module through Gradle, so a JDK must be
installed. See [docs/releasing.md](docs/releasing.md) for signing and releases.

## How it's built

```
shared/          Kotlin Multiplatform: colour maths, tool reference maths,
                 the .cube parser, design tokens, the test pattern. Tested on
                 the JVM; the iPad links it as the LinkCamShared framework.
android/uvc/     The USB Video Class driver: libusb + libuvc + libjpeg-turbo
                 behind a small Kotlin API (JNI, CMake).
android/app/     The Android app: Compose UI, managers, the GLES 3 renderer
                 and its shaders (assets/shaders).
ios/             The iPad app: SwiftUI, AVFoundation capture, and the same
                 shaders ported to Metal (LinkCam/Render).
brand/           Name, glyph and icon. Not open source; see below.
```

A frame takes this path:

1. The capture device delivers a frame.
2. The frame is decoded on the capture thread into Y/Cb/Cr planes.
3. The planes go into a triple buffer, so the renderer never waits on the
   camera.
4. They're uploaded to the GPU once.
5. One fragment shader converts the colour and applies every tool.

Frame lines and safe areas are vector overlays on top, so they're never part
of the picture.

## Licence and trademarks

The code is licensed under the [Apache License 2.0](LICENSE), so you can use
it, change it and ship it, commercially too. The **name, glyph and icon are
trademarks** of Ferret Media and are not covered by that licence. Forks are
welcome, but they need their own name and icon. See
[TRADEMARKS.md](TRADEMARKS.md) and [brand/LICENSE](brand/LICENSE).

Third-party components and their licences are listed in [NOTICE](NOTICE).
