# Changelog

## [0.1.0](https://github.com/FerretMedia/link-cam/commits/v0.1.0) (2026-11-09)

The first release of Link Cam: plug an HDMI capture dongle into your phone's USB-C port and get a clean, low-latency picture of your camera's output, with the tools of a field monitor.

### Monitor

* Live view from USB Video Class capture dongles (MJPEG, YUY2, NV12), decoded once and drawn on the GPU.
* Explicit colour interpretation per source (BT.601, 709, 2020; full or limited range), with a manual override.
* A built-in test pattern (75% bars and a 0–100 IRE ramp) for trying everything without hardware.
* Format, frame rate, latency and dropped frames in a status readout. Phone battery on the picture, landscape both ways, full brightness while monitoring.

### Tools and scopes

* False color with ARRI-style bands, zebras, focus peaking, frame lines (2.39, 1.85, 4:5, 9:16, 1:1), action and title safe, 2× punch-in.
* Histogram and waveform over the picture.

### HDR and noise reduction

* An HDR PQ / HLG view that shows the signal the way Resolve's Rec.709 output does: 100 nits is white, highlights roll off with the BT.2390 curve.
* Optional noise reduction over time: still parts are cleaned over several frames, moving parts never smear.
* Both change only what the monitor shows, never the recording.

### Canon camera link

* Shutter, aperture, ISO and the camera's battery live under the picture, over Wi-Fi ("Remote control (EOS Utility)" mode), with icons for the dial or ring that turns each one.
* Pairing without typing: Link Cam reads the camera's Wi-Fi name and password off its screen through HDMI.
* Saved cameras, reconnected automatically after drops, restarts and backgrounding.

### iPad (new, not yet tested on a device)

* A first iPad app with the monitor, the tools, the scopes, HDR view and noise reduction. The camera link is Android only for now.

### Install

Android 10 or newer with USB host (OTG) support, and a UVC capture dongle (most cheap HDMI capture dongles are UVC).
