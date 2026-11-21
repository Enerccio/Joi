#!/bin/sh
# Builds the manual (docs/, ignored by git; the Manual workflow publishes its own build) with the version of the project and the
# commit it was built from in the label next to the title and in the footer. Run from anywhere; passes extra arguments to `retype build`.
# (Marginalia's manual is also built per language; Joi's is English only for now.)
set -e
cd "$(dirname "$0")/.."
version=$(sed -n '/<artifactId>joi-parent<\/artifactId>/,/<\/version>/s/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -1)
[ -n "$version" ] || version=dev
commit=$(git rev-parse --short HEAD)
override="{\"branding\":{\"label\":\"Manual $version\"},\"footer\":{\"copyright\":\"&copy; Copyright 2026. MIT License. Version $version ($commit).\"}}"
retype build --override "$override" "$@"
