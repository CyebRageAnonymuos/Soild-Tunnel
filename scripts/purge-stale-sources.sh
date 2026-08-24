#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Purge sources that  removed but that still exist in an older checkout.
#
# WHY THIS EXISTS
# ---------------
#  deleted several source files (the in-app updater, the country/location
# picker, the forced-exit policy). When the new sources are copied on top of an
# existing  repository, *added and changed* files are overwritten but the
# *deleted* ones stay behind. Those orphans still reference symbols and string
# resources that no longer exist, so the Kotlin compiler fails with a wall of
# "Unresolved reference" errors (UpdateChecker.kt -> GITHUB_REPO,
# UpdatePrompt.kt -> update_available, ...) even though the shipped source tree
# compiles perfectly on its own. That is exactly why the same sources built
# fine in a fresh repository and failed in the  one.
#
# The build now removes those orphans itself before compiling, so the release
# pipeline can never be broken again by a leftover file from an older version.
#
# Safety: only paths listed in .github/removed-sources.txt are ever touched,
# and only inside app/ , native/engine/soildtunnel/src/ and docs/ .
# ---------------------------------------------------------------------------
set -euo pipefail

MANIFEST=".github/removed-sources.txt"
removed=0

if [ ! -f "$MANIFEST" ]; then
	echo "No removal manifest at $MANIFEST - nothing to purge."
else
	while IFS= read -r raw || [ -n "$raw" ]; do
		path="${raw%%#*}"
		path="$(printf '%s' "$path" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
		[ -n "$path" ] || continue

		case "$path" in
			/*|*..*)
				echo "::error::Refusing suspicious path in $MANIFEST: $path"; exit 1 ;;
			app/*|native/engine/soildtunnel/src/*|docs/*) : ;;
			*)
				echo "::error::Path outside the allowed trees in $MANIFEST: $path"; exit 1 ;;
		esac

		if [ -e "$path" ]; then
			rm -rf -- "$path"
			echo "Removed stale file left over from an older version: $path"
			removed=$((removed + 1))
		fi
	done < "$MANIFEST"

	if [ "$removed" -eq 0 ]; then
		echo "Source tree is already clean - no stale files from older versions."
	else
		echo "Purged $removed stale file(s) left over from an older version."
	fi
fi

# ---------------------------------------------------------------------------
# Safety net: catch ANY remaining orphan that references a string resource
# which no longer exists. This costs a second and fails with a precise message
# instead of a three-minute Gradle run ending in "Unresolved reference".
# ---------------------------------------------------------------------------
STRINGS="app/src/main/res/values/strings.xml"
if [ -f "$STRINGS" ] && [ -d app/src/main/java ]; then
	sed -n 's/.*<string[[:space:]][^>]*name="\([^"]*\)".*/\1/p' "$STRINGS" | sort -u > /tmp/soildtunnel-defined-strings.txt
	# NOTE: only the app's OWN resources are checked. Framework resources
	# (android.R.string.cancel, ...) are never declared in strings.xml and must
	# not be reported as missing.
	grep -rn --include='*.kt' -oE '[A-Za-z0-9_.]*R\.string\.[A-Za-z0-9_]+' app/src/main/java \
		| grep -v ':android\.R\.string\.' \
		| sed -E 's/^([^:]+):([0-9]+):[A-Za-z0-9_.]*R\.string\.(.+)$/\1|\2|\3/' | sort -u > /tmp/soildtunnel-used-strings.txt

	missing=0
	while IFS='|' read -r file line id; do
		[ -n "${id:-}" ] || continue
		if ! grep -qx "$id" /tmp/soildtunnel-defined-strings.txt; then
			echo "::error file=${file},line=${line}::R.string.${id} does not exist (stale reference from an older version)."
			missing=$((missing + 1))
		fi
	done < /tmp/soildtunnel-used-strings.txt

	if [ "$missing" -gt 0 ]; then
		echo "::error::${missing} stale string reference(s). Add the offending file(s) to ${MANIFEST} or restore the strings."
		exit 1
	fi
	echo "String-resource reference check: OK ($(wc -l < /tmp/soildtunnel-used-strings.txt) references, all defined)."
fi
