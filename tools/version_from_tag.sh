#!/usr/bin/env bash
# The release version is the tag: on a build of a v* tag (GITHUB_REF=refs/tags/v1.2.3) every POM of Joi (the root, joi-protocol,
# joi-server, joi-node, joi-desktop, joi-ios, joi-extensions and the extensions in it) gets the version of the tag, 1.2.3, before anything is built, so the
# WAR, the extension JARs and the image are named and labelled by the tag. Any other build keeps the version of pom.xml.
# Run by the workflows (server.yml, test.yml) in every job that builds. Locally, to try it on a copy of the POMs:
# JOI_ROOT=/path/to/copy GITHUB_REF=refs/tags/v1.2.3 tools/version_from_tag.sh
#
# Not `mvn versions:set -DprocessAllModules`: the reactor also holds Marginalia's platform (the submodule), whose version is its own
# (`marginalia.version` in the root pom.xml) and must stay, so only Joi's POMs are rewritten here.
set -euo pipefail

ref="${GITHUB_REF:-}"
if [[ "$ref" != refs/tags/v* ]]; then
  echo "Not a release tag (${ref:-no ref}): the version stays the one of pom.xml"
  exit 0
fi
version="${ref#refs/tags/v}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$ ]]; then
  echo "::error::The tag v$version is not a version (v<major>.<minor>.<patch>, optionally -<suffix>)"
  exit 1
fi

cd "${JOI_ROOT:-$(dirname "$0")/..}"
python3 - "$version" <<'PY'
import pathlib, re, sys

new = sys.argv[1]
root = pathlib.Path("pom.xml")
match = re.search(r"<artifactId>joi-parent</artifactId>\s*<version>([^<]+)</version>", root.read_text(encoding="utf-8"))
if not match:
    sys.exit("::error::pom.xml: the version of joi-parent was not found")
old = match.group(1)
if old == new:
    print(f"The version of pom.xml is the tag's: {new}")
    sys.exit(0)
print(f"::warning::pom.xml says {old}, the tag v{new}: the build takes {new}. Set it in the POMs so that the next builds of master say it too.")

poms = [root, pathlib.Path("joi-protocol/pom.xml"), pathlib.Path("joi-server/pom.xml"), pathlib.Path("joi-node/pom.xml"),
        pathlib.Path("joi-desktop/pom.xml"), pathlib.Path("joi-ios/pom.xml"), pathlib.Path("joi-extensions/pom.xml")] + sorted(pathlib.Path("joi-extensions").glob("*/pom.xml"))
needle = f"<version>{old}</version>"
for pom in poms:
    content = pom.read_text(encoding="utf-8")
    if needle not in content:
        sys.exit(f"::error::{pom}: no {needle} to replace")
    pom.write_text(content.replace(needle, f"<version>{new}</version>"), encoding="utf-8")
    print(f"{pom}: {content.count(needle)} x")
print(f"Version set to {new}")
PY
