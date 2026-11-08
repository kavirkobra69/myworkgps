#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-smartattendance-release.jks}"
ALIAS="${KEY_ALIAS:-smartattendance}"
REPO="${REPO:-}"

if ! command -v keytool >/dev/null 2>&1; then
  printf 'keytool not found. Install a JDK first (Termux: pkg install openjdk-17).\n' >&2
  exit 1
fi

if [ -e "$OUT" ]; then
  printf '%s already exists. Refusing to overwrite it.\n' "$OUT" >&2
  exit 1
fi

PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"

keytool -genkeypair -noprompt \
  -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Smart Attendance, O=MmdKavir, C=IR"

B64="$(base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT" | tr -d '\n')"

if [ -n "$REPO" ] && command -v gh >/dev/null 2>&1; then
  printf '%s' "$B64" | gh secret set KEYSTORE_BASE64 -R "$REPO"
  printf '%s' "$PASS" | gh secret set KEYSTORE_PASSWORD -R "$REPO"
  printf '%s' "$ALIAS" | gh secret set KEY_ALIAS -R "$REPO"
  printf '%s' "$PASS" | gh secret set KEY_PASSWORD -R "$REPO"
  printf 'GitHub secrets were set for %s.\n' "$REPO"
else
  printf '\nAdd these 4 secrets in GitHub: Settings > Secrets and variables > Actions\n\n'
  printf 'KEYSTORE_PASSWORD = %s\n' "$PASS"
  printf 'KEY_ALIAS         = %s\n' "$ALIAS"
  printf 'KEY_PASSWORD      = %s\n' "$PASS"
  printf 'KEYSTORE_BASE64   = (contents of %s.b64)\n' "$OUT"
  printf '%s' "$B64" > "$OUT.b64"
fi

printf '\nKeep %s in a safe place. Losing it means future updates cannot install over this version.\n' "$OUT"
