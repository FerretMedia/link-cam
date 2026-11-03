#!/bin/sh
# Starts a release: sets /VERSION, commits, tags vX.Y.Z and pushes, which runs
# .github/workflows/release.yml. That builds the APK and the iPad app and
# drafts a GitHub release with both, to check and publish.
#
#   scripts/release.sh 0.2.0
set -eu
cd "$(dirname "$0")/.."

version=${1:?usage: scripts/release.sh X.Y.Z}
scripts/version-code.sh "$version" >/dev/null

if [ "$(git rev-parse --abbrev-ref HEAD)" != main ]; then
    echo "Release from main." >&2
    exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
    echo "Commit or stash your changes first." >&2
    exit 1
fi
git fetch --quiet origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
    echo "main isn't the same as origin/main: pull or push first." >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    echo "v$version is tagged already." >&2
    exit 1
fi

echo "$version" > VERSION
git commit --quiet -m "Release $version" VERSION
git tag -a "v$version" -m "Link Cam $version"
printf 'Push main and v%s to start the release build? [y/N] ' "$version"
read -r answer
case "$answer" in
    y | Y)
        git push --quiet origin main "v$version"
        echo "Pushed. Follow it with: gh run watch"
        ;;
    *)
        echo "Not pushed. When ready: git push origin main v$version"
        ;;
esac
