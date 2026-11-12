#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: Scripts/archive-iphone.sh [--output DIRECTORY] [--broker-url HTTPS_URL]

Build and package an unsigned arm64 iPhone archive with the widget extension.
Requires macOS, Xcode, XcodeGen, and Python 3. It does not create an installable IPA.
Default output: build/iphone-artifacts
The optional broker URL may also be set with OURA_BROKER_URL; it is public config.
USAGE
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/build/iphone-artifacts"
BROKER_URL="${OURA_BROKER_URL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output|--broker-url)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      if [[ "$1" == --output ]]; then OUTPUT_DIR="$2"; else BROKER_URL="$2"; fi
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == Darwin ]] || { printf 'An iPhone archive requires macOS and Xcode.\n' >&2; exit 1; }
for tool in xcodebuild xcodegen xcrun ditto python3 shasum; do
  command -v "$tool" >/dev/null || { printf 'Missing required tool: %s\n' "$tool" >&2; exit 1; }
done
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
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
mkdir -p "$REPO_ROOT/build"
ARCHIVE_STAGE="$(mktemp -d "$REPO_ROOT/build/iphone-archive.XXXXXX")"
trap 'rm -rf "$ARCHIVE_STAGE"' EXIT
ARCHIVE_PATH="$ARCHIVE_STAGE/Readout.xcarchive"
cd "$REPO_ROOT"
xcodegen generate
BUILD_ARGS=(archive
  -project Readout.xcodeproj
  -scheme Readout
  -configuration Release
  -destination 'generic/platform=iOS'
  -archivePath "$ARCHIVE_PATH"
  -derivedDataPath "$REPO_ROOT/build/DerivedData-iPhoneArchive"
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="")
if [[ -n "$BROKER_URL" ]]; then BUILD_ARGS+=("OURA_BROKER_URL=$BROKER_URL"); fi
xcodebuild "${BUILD_ARGS[@]}"

APP_PATH="$ARCHIVE_PATH/Products/Applications/Readout.app"
WIDGET_PATH="$APP_PATH/PlugIns/ReadoutWidget.appex"
[[ -d "$APP_PATH" && -d "$WIDGET_PATH" ]] || { printf 'Archive is missing the app or widget extension.\n' >&2; exit 1; }
xcrun lipo "$APP_PATH/Readout" -verify_arch arm64
xcrun lipo "$WIDGET_PATH/ReadoutWidget" -verify_arch arm64
ditto -c -k --sequesterRsrc --keepParent "$ARCHIVE_PATH" "$OUTPUT_DIR/Readout.xcarchive.zip"
(
  cd "$OUTPUT_DIR"
  shasum -a 256 Readout.xcarchive.zip > SHA256SUMS
)
python3 - "$OUTPUT_DIR/BUILD_INFO.json" "$ARCHIVE_PATH" <<'PY'
import datetime, json, pathlib, plistlib, subprocess, sys
output, archive = sys.argv[1:]
products = pathlib.Path(archive) / 'Products/Applications/Readout.app'
with (products / 'Info.plist').open('rb') as file:
    app = plistlib.load(file)
with (products / 'PlugIns/ReadoutWidget.appex/Info.plist').open('rb') as file:
    widget = plistlib.load(file)
def command(*args):
    return subprocess.check_output(args, text=True).strip()
metadata = {
    'commit': command('git', 'rev-parse', 'HEAD'),
    'working_tree_dirty': bool(command('git', 'status', '--porcelain')),
    'built_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'xcode': command('xcodebuild', '-version'),
    'sdk': command('xcodebuild', '-version', '-sdk', 'iphoneos', 'SDKVersion'),
    'platform': 'iOS device',
    'architecture': 'arm64',
    'signed': False,
    'installable': False,
    'app_bundle_id': app['CFBundleIdentifier'],
    'widget_bundle_id': widget['CFBundleIdentifier'],
    'version': app['CFBundleShortVersionString'],
    'build': app['CFBundleVersion'],
    'oura_broker_configured': app.get('OuraBrokerURL', '').strip().startswith('https://'),
}
pathlib.Path(output).write_text(json.dumps(metadata, indent=2) + '\n')
PY
cp "$REPO_ROOT/Scripts/IPHONE_ARTIFACT.md" "$OUTPUT_DIR/INSTALLING.md"
printf 'Unsigned iPhone archive packaged at %s\n' "$OUTPUT_DIR/Readout.xcarchive.zip"
printf 'Signing is required before iPhone installation. Read %s/INSTALLING.md\n' "$OUTPUT_DIR"
