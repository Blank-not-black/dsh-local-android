import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const app = await readFile(new URL('../gateway/public/app.js', import.meta.url), 'utf8')
const html = await readFile(new URL('../gateway/public/index.html', import.meta.url), 'utf8')
const styles = await readFile(new URL('../gateway/public/styles.css', import.meta.url), 'utf8')

test('model settings page exposes the local DSH configuration surface', () => {
  assert.match(html, /data-settings-group="model"/)
  assert.match(html, /id="settings-page-model"/)
  assert.match(html, /id="model-settings-list"/)
  assert.match(styles, /\.model-settings-shell/)
  assert.match(styles, /\.model-provider-card/)
})

test('model settings uses DSH RPCs and never persists API keys in WebView storage', () => {
  for (const method of [
    'llm.providers',
    'settings.describe',
    'credentials.describe',
    'credentials.set',
    'credentials.unset',
    'settings.mutate',
    'llm.discoverModels',
    'settings.openDocument',
  ]) {
    assert.match(app, new RegExp(`(?:rpc|safeRpc)\\('${method.replace('.', '\\.')}'`))
  }

  assert.match(app, /data-model-field="apiKey" value=/)
  assert.doesNotMatch(app, /LS\.(?:get|set|del)\([^)]*apiKey/i)
  assert.doesNotMatch(app, /localStorage\.(?:getItem|setItem|removeItem)\([^)]*apiKey/i)
})

test('model settings keeps secrets write-only and supports model catalog edits', () => {
  assert.match(app, /type="password" autocomplete="off" data-model-field="apiKey"/)
  assert.match(app, /rpc\('settings\.mutate'/)
  assert.match(app, /rpc\('credentials\.set'/)
  assert.match(app, /rpc\('credentials\.unset'/)
  assert.match(app, /rpc\('llm\.discoverModels'/)
  assert.match(app, /data-model-action="add-model"/)
  assert.match(app, /data-model-action="remove-model"/)
})
