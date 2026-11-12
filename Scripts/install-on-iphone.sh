#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: Scripts/install-on-iphone.sh --team TEAM_ID --device DEVICE_UDID [--broker-url HTTPS_URL]
       Scripts/install-on-iphone.sh --list

Build, sign, install, and launch Readout and its widget on a connected iPhone.
Requires macOS, Xcode 15+, XcodeGen, an Apple account added in Xcode, and a trusted
phone with Developer Mode enabled. The signing team must support this app's IDs,
App Group, and HealthKit capability. No signing credentials are sent to GitHub.
The optional broker URL may also be set with OURA_BROKER_URL; it is public config.
USAGE
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEAM_ID=""
DEVICE_ID=""
BROKER_URL="${OURA_BROKER_URL:-}"
LIST_DEVICES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --team|--device|--broker-url)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      case "$1" in
        --team) TEAM_ID="$2" ;;
        --device) DEVICE_ID="$2" ;;
        --broker-url) BROKER_URL="$2" ;;
      esac
      shift 2
      ;;
    --list) LIST_DEVICES=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == Darwin ]] || { printf 'Installing an iPhone build requires macOS and Xcode.\n' >&2; exit 1; }
for tool in xcodebuild xcodegen xcrun codesign python3; do
  command -v "$tool" >/dev/null || { printf 'Missing required tool: %s\n' "$tool" >&2; exit 1; }
done
cd "$REPO_ROOT"
xcodegen generate
if [[ "$LIST_DEVICES" == true ]]; then
  xcodebuild -project Readout.xcodeproj -scheme Readout -showdestinations
  printf '\nUse the physical iPhone ID, not a simulator ID, with --device.\n'
  exit 0
fi
[[ "$TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || { printf 'Supply your 10-character Apple Developer Team ID with --team.\n' >&2; exit 2; }
[[ "$DEVICE_ID" =~ ^[A-Za-z0-9-]{15,64}$ ]] || { printf 'Supply the connected physical iPhone UDID with --device (use --list).\n' >&2; exit 2; }
if [[ -n "$BROKER_URL" ]]; then
  python3 - "$BROKER_URL" <<'PY'
import sys
from urllib.parse import urlsplit
url = urlsplit(sys.argv[1])
if (url.scheme != 'https' or not url.hostname or url.username or url.password
        or url.query or url.fragment):
    sys.exit('Broker URL must be an HTTPS base URL without credentials, query, or fragment.')
PY
fi
DERIVED_DATA="$REPO_ROOT/build/DerivedData-iPhone"
BUILD_ARGS=(build
  -project Readout.xcodeproj
  -scheme Readout
  -configuration Debug
  -destination "platform=iOS,id=$DEVICE_ID"
  -derivedDataPath "$DERIVED_DATA"
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration
  "DEVELOPMENT_TEAM=$TEAM_ID" CODE_SIGN_STYLE=Automatic)
if [[ -n "$BROKER_URL" ]]; then BUILD_ARGS+=("OURA_BROKER_URL=$BROKER_URL"); fi
xcodebuild "${BUILD_ARGS[@]}"

APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphoneos/Readout.app"
[[ -d "$APP_PATH/PlugIns/ReadoutWidget.appex" ]] || { printf 'Signed build is missing the app or widget.\n' >&2; exit 1; }
codesign --verify --deep --strict "$APP_PATH"
APP_BUNDLE_ID="$(python3 - "$APP_PATH/Info.plist" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'rb') as file:
    print(plistlib.load(file)['CFBundleIdentifier'])
PY
)"
xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH"
xcrun devicectl device process launch --device "$DEVICE_ID" "$APP_BUNDLE_ID"
printf 'Readout installed and launched. Add its widgets from your Home or Lock Screen.\n'
