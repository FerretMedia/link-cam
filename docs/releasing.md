# Releasing

One version number covers both apps. It lives in [`/VERSION`](../VERSION).
Version `1.2.3` has build number `10203`, which is the Android
`versionCode` and the iPad's `CFBundleVersion`
([`scripts/version-code.sh`](../scripts/version-code.sh)).

## Making a release

```sh
scripts/release.sh 0.2.0
```

From a clean, up-to-date `main`, this script:

1. Writes the version.
2. Commits and tags `v0.2.0`.
3. Asks before pushing.

The push runs [`release.yml`](../.github/workflows/release.yml), which:

- builds `LinkCam-0.2.0.apk`, signed with the release key;
- builds `LinkCam-0.2.0-unsigned.ipa`, for sideloading;
- if the App Store Connect secrets are set, also signs the iPad build and
  uploads it to TestFlight;
- drafts a GitHub release with the files attached. Check the draft and
  publish it.

The repository is private, so macOS runner minutes count against the plan,
at ten times the Linux rate. An iPad build is about 15 minutes, so it runs
only for releases, never on every push.

## Android

Release builds are signed with a key kept outside the repository:

- **On a developer's machine:** `keystore.properties` at the repository root
  (gitignored) points at the keystore and holds its passwords. With it,
  `./gradlew :android:app:assembleRelease` gives a signed APK.
- **In CI:** the same values come from four Actions secrets:
  `LINKCAM_KEYSTORE_BASE64`, `LINKCAM_KEYSTORE_PASSWORD`, `LINKCAM_KEY_ALIAS`
  and `LINKCAM_KEY_PASSWORD`.
  [`scripts/set-android-signing-secrets.sh`](../scripts/set-android-signing-secrets.sh)
  copies them from `keystore.properties` with `gh secret set`.

**Back up the keystore and its passwords offline.** Every update to an
installed app, and to a Play listing, has to be signed with the same key. If
the key is lost, people have to uninstall and reinstall to update.

An APK signed with the release key can't be installed over a debug build,
because the signatures differ. Uninstall the debug build first, which clears
its settings and paired cameras.

## iPad

**On the Mac**, for trying a change on the iPad:

```sh
brew install xcodegen                 # once
cp ios/Config/Signing.local.xcconfig.example ios/Config/Signing.local.xcconfig
#   …and set DEVELOPMENT_TEAM to your team ID
ios/install.sh                        # builds and installs on the connected iPad
```

Or run `ios/generate.sh` and open `ios/LinkCam.xcodeproj` in Xcode. Gradle
needs a JDK (`brew install openjdk@21`, or any JDK 17 or newer). The Xcode
build calls Gradle to build the shared Kotlin module.

A free Apple account can sign for your own devices, but those builds stop
opening after 7 days. To distribute the app, through TestFlight or the App
Store, you need the Apple Developer Program.

**The unsigned IPA** from a release works with sideloading tools such as
AltStore or Sideloadly, which sign it with the installer's own Apple ID.

**TestFlight from CI** needs four secrets:

| Secret | What it is |
|---|---|
| `APPLE_TEAM_ID` | The 10-character team ID |
| `ASC_KEY_ID` | An App Store Connect API key's ID (Users and Access › Integrations › App Store Connect API, role App Manager) |
| `ASC_ISSUER_ID` | That page's Issuer ID |
| `ASC_KEY_P8_BASE64` | The key's `.p8` file, base64-encoded (`base64 -i AuthKey_XXXX.p8`) |

The app also needs a record in App Store Connect with bundle ID
`cool.linker.cam`. The build signs itself through the API key, so no
certificates or profiles are kept in the repository.
