#!/usr/bin/env bash
# Downloads Sarasa Term SC (CJK glyphs exactly two columns wide) into assets. Not kept in git.
set -euo pipefail
VER=1.0.42
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/fonts"
[ -f "$DIR/SarasaTermSC-Regular.ttf" ] && { echo "Already there: $DIR/SarasaTermSC-Regular.ttf"; exit 0; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -sSfL -o "$TMP/f.7z" "https://github.com/be5invis/Sarasa-Gothic/releases/download/v$VER/SarasaTermSC-TTF-Unhinted-$VER.7z"
7z e -y -o"$TMP" "$TMP/f.7z" SarasaTermSC-Regular.ttf >/dev/null
mkdir -p "$DIR"
mv "$TMP/SarasaTermSC-Regular.ttf" "$DIR/"
echo "Downloaded: $DIR/SarasaTermSC-Regular.ttf"
