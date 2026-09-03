#!/bin/sh
set -eu

GRADLE_VERSION=8.13
GRADLE_SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/decimen-bootstrap"
INSTALL_DIR="$CACHE_ROOT/gradle-${GRADLE_VERSION}"
ZIP_FILE="$CACHE_ROOT/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_ROOT"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 -o "$ZIP_FILE" "$GRADLE_URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$GRADLE_URL"
    else
      echo "ERROR: curl or wget is required for the first build." >&2
      exit 1
    fi
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA=$(sha256sum "$ZIP_FILE" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA=$(shasum -a 256 "$ZIP_FILE" | awk '{print $1}')
  else
    echo "ERROR: sha256sum or shasum is required." >&2
    exit 1
  fi
  if [ "$ACTUAL_SHA" != "$GRADLE_SHA256" ]; then
    rm -f "$ZIP_FILE"
    echo "ERROR: Gradle archive checksum mismatch." >&2
    exit 1
  fi

  TMP_DIR="$CACHE_ROOT/unpack-$$"
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  unzip -q "$ZIP_FILE" -d "$TMP_DIR"
  rm -rf "$INSTALL_DIR"
  mv "$TMP_DIR/gradle-${GRADLE_VERSION}" "$INSTALL_DIR"
  rm -rf "$TMP_DIR"
fi

exec "$INSTALL_DIR/bin/gradle" "$@"
