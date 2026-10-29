#!/usr/bin/env bash
# Creates your private Google Play *upload* key and prints the values for the GitHub secrets.
# Keep the .jks file and passwords somewhere safe (password manager + offline backup).
# Never commit it. With Play App Signing, Google holds the real app-signing key; if you lose
# this upload key you can ask Google to reset it.
set -euo pipefail
OUT="${1:-holos-upload.jks}"
ALIAS="upload"
read -r -s -p "Choose a keystore password (min 6 chars): " PASS; echo
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Holos, O=Pixtane"
echo
echo "Add these repository secrets on GitHub (Settings → Secrets and variables → Actions):"
echo "  HOLOS_UPLOAD_KEY_ALIAS      = $ALIAS"
echo "  HOLOS_UPLOAD_STORE_PASSWORD = (the password you typed)"
echo "  HOLOS_UPLOAD_KEY_PASSWORD   = (the same password)"
echo "  HOLOS_UPLOAD_KEYSTORE_B64   = contents of ${OUT}.b64 (created now)"
base64 -w0 "$OUT" > "${OUT}.b64" 2>/dev/null || base64 -i "$OUT" -o "${OUT}.b64"
echo "Done: $OUT and ${OUT}.b64"
