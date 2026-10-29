#!/usr/bin/env bash
#
# Creates the signing key used to build Shield.
#
# Why this is a separate script rather than a committed key:
#
# Android will only install an update over an existing app if both are signed by
# the same key. If you hold the signing key, you can build a version of Shield
# with the blocking removed and install it over the working one without
# uninstalling anything — no cooling-off period, no partner code. That is the
# single cleanest bypass of everything this app does.
#
# So: generate the key, then put it somewhere you genuinely cannot get at in a
# weak moment. Give it to your accountability partner, or store it offline. If
# you lose it entirely, you can still build Shield again later, but installing
# that build will require uninstalling the old one first — which the uninstall
# protection will make you wait and ask for approval to do. That is working as
# intended.
#
# Usage:
#   ./tools/make-keystore.sh                 # prompts for a password
#   KS_PASS=yourpassword ./tools/make-keystore.sh
#
set -euo pipefail

KEYSTORE_DIR="$(cd "$(dirname "$0")/.." && pwd)/keystore"
KEYSTORE_PATH="$KEYSTORE_DIR/release.jks"
ALIAS="${KS_ALIAS:-blocker}"

if [ -f "$KEYSTORE_PATH" ]; then
    echo "A keystore already exists at $KEYSTORE_PATH"
    echo "Delete it first if you really want a new one (this breaks updating any"
    echo "copy of Shield already installed)."
    exit 1
fi

if [ -z "${KS_PASS:-}" ]; then
    read -r -s -p "Keystore password (at least 6 characters): " KS_PASS
    echo
    read -r -s -p "Confirm password: " KS_PASS_CONFIRM
    echo
    if [ "$KS_PASS" != "$KS_PASS_CONFIRM" ]; then
        echo "Passwords do not match."
        exit 1
    fi
fi

mkdir -p "$KEYSTORE_DIR"

keytool -genkeypair \
    -keystore "$KEYSTORE_PATH" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10950 \
    -storepass "$KS_PASS" \
    -keypass "$KS_PASS" \
    -dname "CN=Shield Personal Build, OU=Personal, O=Shield, C=IN"

echo
echo "Created $KEYSTORE_PATH"
echo
echo "Build a signed release with:"
echo "  ./gradlew assembleRelease -PKS_PASS=<password> -PKS_KEY_PASS=<password> -PKS_ALIAS=$ALIAS"
echo
echo "Now move this file off the phone and off this computer, or hand it to your"
echo "accountability partner. Do not commit it."
