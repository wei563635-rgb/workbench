#!/usr/bin/env sh
set -eu

TARGET=${1:-frontend/src}

if rg -n "<select|<option|alert\\(|window\\.alert|confirm\\(|window\\.confirm|prompt\\(|window\\.prompt" "$TARGET"; then
  echo "Native browser controls/dialogs found in $TARGET" >&2
  exit 1
fi

echo "No native select/option or browser alert/confirm/prompt found in $TARGET"
