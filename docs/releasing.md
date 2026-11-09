# Branches, builds and releases

Link Cam uses the same flow as many large open source projects: a `dev`
branch where work lands, a `main` branch that only ever holds released or
about-to-be-released code, and [release-please] to number versions and write
the changelog from commit titles.

[release-please]: https://github.com/googleapis/release-please

```
 feature branch ──PR (squash)──▶ dev ──PR (merge commit)──▶ main ──release PR──▶ v0.2.0
       │                          │                           │                    │
  CI + debug APK          signed "Link Cam Dev"       release-please opens   APK + IPA on
  on the PR               on the dev-build release    or updates a release   the GitHub
                                                      PR                     release
                          ◀──────────── main merged back into dev ─────────────────┘
```

| What | When | Where to download it | Who it's for |
|---|---|---|---|
| **PR build** | Every push to a pull request | Link in a comment on the PR (a zip, kept 14 days) | Reviewers |
| **Dev build** | Every merge into `dev` | [`dev-build` pre-release][dev], always `LinkCam-dev.apk` | Testers and early adopters |
| **Release** | Merging the release PR | [Latest release][latest] | Everyone |

[dev]: https://github.com/FerretMedia/link-cam/releases/tag/dev-build
[latest]: https://github.com/FerretMedia/link-cam/releases/latest

The dev build is called **Link Cam Dev** and has its own app ID
(`cool.linker.cam.dev`), so it installs next to the stable app, with its own
settings and paired cameras. Each dev build updates the one before.

## Versions

One version number covers both apps. It lives in [`/VERSION`](../VERSION),
and release-please changes it; don't edit it by hand. Version `1.2.3` has
build number `10203`, which is the Android `versionCode` and the iPad's
`CFBundleVersion` ([`scripts/version-code.sh`](../scripts/version-code.sh)).
Dev builds are named like `0.2.0+dev.153.abc1234`: the last release, the
commit count on `dev`, and the commit.

Before 1.0, a breaking change raises the minor number, not the major one.

## Commit titles

Pull requests into `dev` are squash-merged, so each one becomes a single
commit whose message is the PR title. release-please reads those titles, so
they follow [Conventional Commits](https://www.conventionalcommits.org). The
**PR title** check enforces it.

| Title | Next version | In the changelog |
|---|---|---|
| `feat: show the camera's battery level` | 0.2.0 → 0.3.0 | New |
| `fix(usb): reconnect after the dongle resets` | 0.2.0 → 0.2.1 | Fixes |
| `perf: decode MJPEG on two threads` | 0.2.0 → 0.2.1 | Faster |
| `feat!: drop Android 10` | 0.2.0 → 0.3.0 (2.0.0 → 3.0.0 after 1.0) | New, marked breaking |
| `docs:` `refactor:` `test:` `build:` `ci:` `chore:` | none on its own | hidden |

The part in brackets is an optional scope. If a title is wrong after merging,
edit the merged PR's body to add an override:

```
BEGIN_COMMIT_OVERRIDE
fix: the corrected title
END_COMMIT_OVERRIDE
```

## One-time setup

Do these once. Steps 1 and 2 have to happen before the first dev build or
release can be signed.

1. **Back up the Android release key** and its passwords somewhere offline
   (a password manager and a USB stick). Every update to an installed app has
   to be signed with the same key. If the key is lost, people have to
   uninstall and reinstall to update. It's in
   `~/.config/ferret-media/link-cam/release.jks`, and `keystore.properties`
   (gitignored) points at it.

2. **Put the key in the repository's Actions secrets:**

   ```sh
   scripts/set-android-signing-secrets.sh
   ```

   This sets `LINKCAM_KEYSTORE_BASE64`, `LINKCAM_KEYSTORE_PASSWORD`,
   `LINKCAM_KEY_ALIAS` and `LINKCAM_KEY_PASSWORD` from `keystore.properties`
   with `gh secret set`. Secrets are never shown to pull requests from forks.

3. **Make the repository public**, if people outside the organisation should
   download the app (Settings › General › Danger Zone › Change visibility).
   Releases of a private repository can only be downloaded by its members.
   Public repositories also get Actions minutes for free, including the
   macOS runner for the iPad build, and branch rules. Before you do, check
   that nothing private is in the history (`PLAN.md` never was; it's
   gitignored).

4. **Set up the repository:**

   ```sh
   scripts/setup-github-repo.sh
   ```

   It creates `dev` if needed and makes it the default branch, so new pull
   requests target it. It makes squash merges use the PR title, lets GitHub
   Actions open pull requests (release-please needs that), and, on a public
   repository, protects `main` and `dev`. Run it again after making the
   repository public, to add the branch rules.

   If the organization forbids GitHub Actions to open pull requests, the
   script says so and prints the command an organization admin can run
   (FerretMedia allows it since November 2026).

## Everyday work

1. Branch from `dev`:

   ```sh
   git switch dev && git pull
   git switch -c feature/waveform
   ```

2. Push and open a pull request into `dev` with a Conventional Commit title:

   ```sh
   git push -u origin feature/waveform
   gh pr create --base dev --title "feat: add a waveform scope"
   ```

3. CI runs the tests, builds the app and comments a debug APK link on the PR.
   A debug build has another signature than the dev and release builds, so
   uninstall those first, or try it on another device.

4. **Squash and merge.** A minute or two later the [dev build][dev] has the
   change.

## Making a release

1. **Promote `dev` to `main`.** Open a pull request from `dev` into `main`:

   ```sh
   gh pr create --base main --head dev --title "chore: promote dev to main" \
     --body "Everything on dev, for the next release."
   ```

   Merge it with **Create a merge commit**, never squash. Squashing would turn
   every change into one commit and the changelog into one line.

2. **release-please opens a release PR** on `main`, titled like
   `chore: release 0.2.0`, within a minute. It bumps `/VERSION` and adds the
   new section to [`CHANGELOG.md`](../CHANGELOG.md). Each later promotion
   updates the same PR, so you can collect several before releasing. Read the
   changelog in it and edit the PR's text if you like.

   The release PR has no CI checks, because GitHub doesn't run workflows on
   pull requests that GitHub Actions opened. That's expected: it only
   changes `/VERSION` and the changelog, and the code was checked on `dev`.

3. **Merge the release PR** (squash is fine). Then
   [`release.yml`](../.github/workflows/release.yml):

   - tags `v0.2.0` and makes a draft GitHub release with the changelog;
   - builds `LinkCam-0.2.0.apk`, signed with the release key, and its
     SHA-256;
   - builds `LinkCam-0.2.0-unsigned.ipa`, for sideloading, and, if the App
     Store Connect secrets are set, sends a signed build to TestFlight;
   - attaches the files, adds install notes and **publishes the release**;
   - merges `main` back into `dev`, so dev builds carry the new version. If
     that can't be done automatically, it opens a PR from `main` into `dev`;
     merge it with a merge commit.

   Follow it with `gh run watch`, or in the Actions tab. The Android part
   takes about 10 minutes, the iPad part about 15. If the iPad build fails,
   the release is published with the APK only; fix the build and rebuild.

### Choosing the version yourself

release-please picks the version from the commit titles. To pick it
yourself, for example `0.1.0` for the first release, which would otherwise be
`0.2.0`, put a `Release-As` line in the message of the merge commit when you
promote `dev` to `main`:

```sh
gh pr merge 12 --merge --body "Release-As: 0.1.0"
```

### Editing the release notes

The release notes come from the release PR's description, and the changelog
from `CHANGELOG.md` on its branch. To reword them, edit the description and
push the same change to `CHANGELOG.md` on the `release-please--branches--main…`
branch, then merge. A push to `main` before then makes release-please write
the PR again from the commits, so edit last.

### Fixing a broken release

- **A file is missing or broken:** Actions › Release › Run workflow, with the
  tag (`v0.2.0`). It rebuilds that release's files and replaces them.
- **The release itself is wrong:** don't move or reuse the tag. Merge a fix
  into `dev`, promote it, and release `0.2.1`. People who installed
  `0.2.0` get the fix as an ordinary update.

### Hotfix

For an urgent fix with unreleased work on `dev` that isn't ready: branch from
`main`, open the PR into `main` (title `fix: …`), and squash-merge it.
release-please opens a release PR for the patch version. After the release,
the workflow merges `main` back into `dev`, fix included.

## Android signing

Release and dev builds are signed with a key kept outside the repository:

- **On a developer's machine:** `keystore.properties` at the repository root
  (gitignored) points at the keystore and holds its passwords. With it,
  `./gradlew :android:app:assembleRelease` gives a signed APK, and
  `./gradlew :android:app:assembleDev` a signed Link Cam Dev.
- **In CI:** the same values come from the four Actions secrets above.

An APK signed with the release key can't be installed over a debug build,
because the signatures differ. Uninstall the debug build first, which clears
its settings and paired cameras. The dev build doesn't have this problem: it's
a separate app.

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

The iPad app is built only for releases, not for every PR or dev build: a
macOS runner is slow, and on a private repository its minutes count ten
times.

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

## Troubleshooting

| Problem | Fix |
|---|---|
| Dev build or release fails at "Signing key" | Run `scripts/set-android-signing-secrets.sh` (one-time setup, step 2). |
| No release PR appears after promoting | The merged commits have no `feat`, `fix` or `perf` titles, so there's nothing to release. Or Actions can't open PRs: run `scripts/setup-github-repo.sh`. |
| "PR title" check fails | Edit the title to `type: description`, like `fix: …`. |
| "Tag doesn't match VERSION" | `/VERSION` was edited by hand. Let release-please own it. |
| Users can't download the release | The repository is private (one-time setup, step 3). |
| The phone says "App not installed" | It's signed differently from the installed copy, usually a debug build. Uninstall that one first. |
