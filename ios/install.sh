#!/bin/sh
# Builds the iPad app and installs it on an iPad connected to this Mac, signed
# with your own Apple account: the quickest way to try a change on the device.
#
#   ios/install.sh            the first connected iPad
#   ios/install.sh <device>   a device id or name from `xcrun devicectl list devices`
#
# Needs DEVELOPMENT_TEAM in Config/Signing.local.xcconfig and that Apple ID
# signed in under Xcode › Settings › Accounts. A free account works; its
# builds stop opening after 7 days.
set -eu
cd "$(dirname "$0")"
./generate.sh >/dev/null

device=${1:-$(xcrun devicectl list devices 2>/dev/null | awk '/iPad/ && /available|connected/ { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9A-F]{8}-/) { print $i; exit } }')}
[ -n "$device" ] || { echo "No iPad found. Connect one, unlock it and trust this Mac." >&2; exit 1; }

xcodebuild -project LinkCam.xcodeproj -scheme LinkCam -configuration Debug \
    -destination "generic/platform=iOS" -derivedDataPath build -allowProvisioningUpdates \
    build | tail -n 20
xcrun devicectl device install app --device "$device" build/Build/Products/Debug-iphoneos/LinkCam.app
xcrun devicectl device process launch --device "$device" cool.linker.cam || true
