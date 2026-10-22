#!/usr/bin/env bash
# Prints the release tag for a branch: "latest" for main, else "preview-<sanitized branch>".
# Usage: release-tag.sh <branch>
set -euo pipefail
branch="$1"
if [ "$branch" = "main" ]; then
  echo "latest"
else
  safe="$(printf '%s' "$branch" | tr -c 'A-Za-z0-9._-' '-' | sed -e 's/--*/-/g' -e 's/^-//' -e 's/-$//' | cut -c1-60)"
  echo "preview-${safe}"
fi
