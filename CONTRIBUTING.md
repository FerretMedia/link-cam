# Contributing

Thanks for helping. A few things to know before opening a pull request.

## Licence and sign-off

Contributions are accepted under the [Apache License 2.0](LICENSE), the same
licence as the project ("inbound = outbound"). Sign off every commit to
certify the [Developer Certificate of Origin](https://developercertificate.org):

```sh
git commit -s
```

Brand files in `/brand` are not open for contributions.

## Pull requests

- **Branch from `dev` and open the pull request into `dev`.** `main` only
  gets released code.
- **Title it as a [Conventional Commit](https://www.conventionalcommits.org)**,
  like `feat: add a waveform scope` or `fix(usb): reconnect after a reset`.
  Pull requests are squash-merged, so the title becomes the commit, the
  changelog line and the next version number. A check enforces it.
- CI comments a debug APK on your pull request, to try the change on a device.

[docs/releasing.md](docs/releasing.md) explains the branches, the builds and
how releases are made.

## Building

You need JDK 17 or newer and the Android SDK. Gradle downloads the rest,
including the right JDK for the build and the native libraries.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # never commit this file
./gradlew :shared:jvmTest              # shared rules, runs on any machine
./gradlew :android:app:assembleDebug   # the app
```

## How the code is laid out

- **`shared/`** holds the rules both apps must agree on (colour maths, the
  tools' reference implementations, the `.cube` parser, design tokens). It
  never touches pixels at frame rate, and every rule in it has a test.
- **Composables render, managers own state.** A composable takes values and
  callbacks. State lives in a manager (`SourceManager`, `ToolsManager`), and
  managers call services (`UsbService`, `SettingsStore`), never the platform
  directly.
- **The shader implements the shared rules.** If you change a threshold or a
  colour, change it in `shared/` and pass it to the shader; don't hard-code it
  in GLSL.
- Comments explain *why*, in full sentences.

## Never commit

Signing keys, `local.properties`, `.env` files, API keys or anything personal.
`.gitignore` covers the usual names; check `git status` before you push.
