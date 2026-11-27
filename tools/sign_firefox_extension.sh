#!/bin/sh
# Builds the browser companion (joi-browser, the Firefox extension) and has Mozilla sign it as an unlisted add-on: it is uploaded to
# addons.mozilla.org only to be signed, not listed in the catalogue. The signed file is joi-browser/target/joi-browser-<version>.xpi,
# installed in Firefox from the file (Add-ons -> gear -> Install Add-on From File).
#
#   tools/sign_firefox_extension.sh              lint, sign the version in src/manifest.json
#   tools/sign_firefox_extension.sh 0.1.1        set that version in src/manifest.json and package.json first, then sign
#
# The AMO keys (Developer Hub -> Tools -> Manage API Keys) are read from joi-browser/firefox.jwt.issuer and firefox.jwt.secret
# (in .git/info/exclude, never in Git), else from WEB_EXT_API_KEY and WEB_EXT_API_SECRET. AMO signs each version once: a version
# that was signed already is refused, so give a new one. The GitHub workflow browser.yml does the same for a v* tag.
set -e
cd "$(dirname "$0")/../joi-browser"

if [ -n "$1" ]; then
    case "$1" in
        *[!0-9.]*|.*|*.|*..*) echo "not a Firefox version (numbers and dots): $1" >&2; exit 2 ;;
    esac
    python3 - "$1" <<'PY'
import json, pathlib, sys
for name in ("src/manifest.json", "package.json"):
    path = pathlib.Path(name)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["version"] = sys.argv[1]
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
PY
fi
version=$(python3 -c 'import json; print(json.load(open("src/manifest.json"))["version"])')

if [ -f firefox.jwt.issuer ] && [ -f firefox.jwt.secret ]; then
    WEB_EXT_API_KEY=$(tr -d '[:space:]' < firefox.jwt.issuer)
    WEB_EXT_API_SECRET=$(tr -d '[:space:]' < firefox.jwt.secret)
    export WEB_EXT_API_KEY WEB_EXT_API_SECRET
fi
if [ -z "$WEB_EXT_API_KEY" ] || [ -z "$WEB_EXT_API_SECRET" ]; then
    echo "no AMO keys: put them in joi-browser/firefox.jwt.issuer and firefox.jwt.secret, or WEB_EXT_API_KEY / WEB_EXT_API_SECRET" >&2
    exit 1
fi

npm install --prefer-offline --no-audit --no-fund
npm run lint
rm -f target/*.xpi
echo "Signing version $version (this uploads it to addons.mozilla.org; it takes a minute or two)"
npm run sign
signed=$(ls target/*.xpi | head -n 1)
mv "$signed" "target/joi-browser-$version.xpi"
echo "Signed: joi-browser/target/joi-browser-$version.xpi"
