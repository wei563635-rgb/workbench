#!/usr/bin/env sh
set -eu

FILE=${1:-frontend/src/App.vue}

if rg -n "entry-switch|绑定到项目|关联项目|linkSelectedRun|projectLinks" "$FILE"; then
  echo "Removed project-link or entry-switch UI still exists in $FILE" >&2
  exit 1
fi

rg -n "组织架构|organizationDepartments|departmentTaskSummary|全部部门任务已完成|部门任务已完成" "$FILE" >/dev/null
echo "Frontend delivery-gap checks passed"
