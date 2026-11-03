#!/bin/sh
# Generates LinkCam.xcodeproj from project.yml, with the version from /VERSION.
set -eu
cd "$(dirname "$0")"

version=$(tr -d '[:space:]' < ../VERSION)
code=$(../scripts/version-code.sh "$version")
cat > Config/Version.xcconfig <<VERSION
// Written by generate.sh from /VERSION. Not committed.
MARKETING_VERSION = $version
CURRENT_PROJECT_VERSION = $code
VERSION

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "XcodeGen is needed: brew install xcodegen" >&2
    exit 1
fi
xcodegen generate --spec project.yml
