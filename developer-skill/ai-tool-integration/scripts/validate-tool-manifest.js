#!/usr/bin/env node
const fs = require('fs')

const file = process.argv[2]
if (!file) {
  console.error('Usage: validate-tool-manifest.js path/to/tool-manifest.json')
  process.exit(2)
}

const manifest = JSON.parse(fs.readFileSync(file, 'utf8'))
const required = ['toolKey', 'name', 'version', 'departmentKey', 'entry', 'integrationType', 'status', 'callbackEnabled', 'aiGatewayRequired']
const errors = []

for (const key of required) {
  if (!(key in manifest)) errors.push(`missing required field: ${key}`)
}

if (manifest.toolKey && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(manifest.toolKey)) {
  errors.push('toolKey must use lowercase letters, numbers, and hyphens')
}

if (manifest.integrationType && !['static', 'iframe', 'external'].includes(manifest.integrationType)) {
  errors.push('integrationType must be static, iframe, or external')
}

if (manifest.status && !['normal', 'maintenance', 'unopened', 'disabled'].includes(manifest.status)) {
  errors.push('status must be normal, maintenance, unopened, or disabled')
}

if (manifest.status === 'normal' && manifest.integrationType !== 'external') {
  if (manifest.callbackEnabled !== true) errors.push('normal non-external tools must set callbackEnabled=true')
  if (manifest.aiGatewayRequired !== true) errors.push('normal non-external tools must set aiGatewayRequired=true')
}

if (errors.length) {
  console.error('Manifest validation failed:')
  for (const error of errors) console.error(`- ${error}`)
  process.exit(1)
}

console.log('Manifest validation passed')
