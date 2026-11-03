#!/bin/sh
# Copies the Android release key from keystore.properties (gitignored) into
# the GitHub repository's Actions secrets, for the release workflow.
set -eu
cd "$(dirname "$0")/.."

prop() { sed -n "s/^$1=//p" keystore.properties; }
store=$(prop storeFile)
[ -f "$store" ] || { echo "No keystore at $store" >&2; exit 1; }

base64 < "$store" | tr -d '\n' | gh secret set LINKCAM_KEYSTORE_BASE64
prop storePassword | tr -d '\n' | gh secret set LINKCAM_KEYSTORE_PASSWORD
prop keyAlias | tr -d '\n' | gh secret set LINKCAM_KEY_ALIAS
prop keyPassword | tr -d '\n' | gh secret set LINKCAM_KEY_PASSWORD
echo "Android signing secrets set."
