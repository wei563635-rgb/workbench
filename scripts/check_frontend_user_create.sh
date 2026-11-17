#!/usr/bin/env sh
set -eu

FILE=${1:-frontend/src/App.vue}

if rg -n "addDemoUser|新增演示用户|role: 'MEMBER'|role: \"MEMBER\"" "$FILE"; then
  echo "Legacy demo-user creation remains in $FILE" >&2
  exit 1
fi

rg -n "openUserCreateDialog|新增用户" "$FILE" >/dev/null
echo "User creation UI no longer uses demo-user defaults"
