#!/usr/bin/env bash
# Builds a DeskCam release, and with `publish` puts it on GitHub.
#
# A release is three things built from one tagged commit: the APK signed with the release
# key, the deskcam CLI for Linux and macOS, and SHA256SUMS over all of them. The version is
# VERSION at the top of the repository and the tag is vVERSION.
#
# The key never enters the repository. It is read from ~/.local/share/deskcam-release/
# (deskcam-release.p12 and a password file) unless DESKCAM_RELEASE_KEYSTORE and
# DESKCAM_RELEASE_PASS say otherwise, and the APK is refused if it was signed with anything
# but the certificate published in the README.
#
#   scripts/release.sh           build into dist/ and check it; publish nothing
#   scripts/release.sh publish   then tag, push the tag, and create the GitHub release
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(tr -d ' \n' < VERSION)"
TAG="v$VERSION"
KEYDIR="$HOME/.local/share/deskcam-release"
RELEASE_CERT="638810f09d75a8f2cdd9c85c9139bae01f67f7f8b6095c6d06de5c2b1beca6a9"

export DESKCAM_RELEASE_KEYSTORE="${DESKCAM_RELEASE_KEYSTORE:-$KEYDIR/deskcam-release.p12}"
if [ -z "${DESKCAM_RELEASE_PASS:-}" ]; then
    [ -r "$KEYDIR/password" ] || { echo "no release key password: set DESKCAM_RELEASE_PASS" >&2; exit 1; }
    DESKCAM_RELEASE_PASS="$(cat "$KEYDIR/password")"
fi
export DESKCAM_RELEASE_PASS

# The release has to be the tagged source, so nothing may be uncommitted.
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "commit first" >&2; exit 1; }
grep -q "^## \[$VERSION\]" CHANGELOG.md || { echo "CHANGELOG.md has no section for $VERSION" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "$TAG already exists; raise VERSION for a new release" >&2
    exit 1
fi

DIST="$ROOT/dist"
rm -rf "$DIST"
mkdir -p "$DIST"

echo ">> apk $VERSION"
./backend/build.sh
cp backend/build/deskcam.apk "$DIST/deskcam.apk"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/${BT_VER:-37.0.0}"
cert="$("$BT/apksigner" verify --print-certs "$DIST/deskcam.apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
[ "$cert" = "$RELEASE_CERT" ] || { echo "the APK is not signed with the release key: $cert" >&2; exit 1; }

echo ">> cli $VERSION"
for target in linux/amd64 linux/arm64 darwin/amd64 darwin/arm64; do
    os="${target%/*}"
    arch="${target#*/}"
    (cd frontend/go && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
        go build -trimpath -ldflags "-s -w" -o "$DIST/deskcam-$os-$arch" .)
done

echo ">> checksums"
(cd "$DIST" && sha256sum deskcam.apk deskcam-* > SHA256SUMS)
cat "$DIST/SHA256SUMS"

notes="$DIST/notes.md"
awk -v head="## [$VERSION]" 'index($0, head) == 1 { on = 1; next } /^## \[/ { on = 0 } on' CHANGELOG.md \
    | sed '/^\[[0-9][0-9.]*\]: /d' > "$notes"
cat >> "$notes" <<NOTES

### Install

- On the phone: \`deskcam.apk\`. \`deskcam serve\` shows a code that downloads it, or install it with \`adb install -r -g deskcam.apk\`.
- On the workstation: the \`deskcam\` binary for your system, on your path. See the README's Quick Start.
- Release certificate SHA-256: \`$RELEASE_CERT\`. Check with \`apksigner verify --print-certs deskcam.apk\`.
- A phone running a home build must uninstall it before installing this one, because the signing keys differ.
NOTES

if [ "${1:-}" != "publish" ]; then
    echo
    echo "built $TAG into dist/. Run 'scripts/release.sh publish' to tag it and release it."
    exit 0
fi

echo ">> publish $TAG"
git tag -a "$TAG" -m "DeskCam $VERSION"
git push origin "$TAG"
gh release create "$TAG" --verify-tag --title "DeskCam $VERSION" --notes-file "$notes" \
    "$DIST/deskcam.apk" "$DIST"/deskcam-* "$DIST/SHA256SUMS"
