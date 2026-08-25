import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repo = fileURLToPath(new URL('..', import.meta.url))
const app = await readFile(new URL('../gateway/public/app.js', import.meta.url), 'utf8')
const html = await readFile(new URL('../gateway/public/index.html', import.meta.url), 'utf8')

test('local UI has a distinct mode and remote-only controls have stable hooks', () => {
  assert.match(app, /const LOCAL_MODE = new URLSearchParams\(location\.search\)\.get\('local'\) === '1'/)
  assert.match(app, /settings-group-servers/)
  assert.match(app, /settings-token-row/)
  assert.match(app, /settings-bg-poll-row/)
  assert.match(html, /id="settings-group-servers"/)
  assert.match(html, /id="settings-token-row"/)
  assert.match(html, /id="settings-bg-poll-row"/)
})
