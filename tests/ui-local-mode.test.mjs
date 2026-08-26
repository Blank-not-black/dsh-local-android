import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repo = fileURLToPath(new URL('..', import.meta.url))
const app = await readFile(new URL('../gateway/public/app.js', import.meta.url), 'utf8')
const html = await readFile(new URL('../gateway/public/index.html', import.meta.url), 'utf8')
const mainActivity = await readFile(new URL('../app/src/main/java/com/dshmobile/shell/MainActivity.kt', import.meta.url), 'utf8')

test('local UI has a distinct mode and remote-only controls have stable hooks', () => {
  assert.match(app, /const LOCAL_MODE = new URLSearchParams\(location\.search\)\.get\('local'\) === '1'/)
  assert.match(app, /settings-group-servers/)
  assert.match(app, /settings-token-row/)
  assert.match(app, /settings-bg-poll-row/)
  assert.match(html, /id="settings-group-servers"/)
  assert.match(html, /id="settings-token-row"/)
  assert.match(html, /id="settings-bg-poll-row"/)
})

test('background lifecycle pauses WebView watchdog and realtime reconnects', () => {
  assert.match(mainActivity, /private var activityInForeground = false/)
  assert.match(mainActivity, /override fun onStop\(\)[\s\S]*freezeHandler\.removeCallbacks\(freezeRunnable\)/)
  assert.match(mainActivity, /dispatchWebLifecycle\(\)/)
  assert.match(app, /let nativeBackgrounded = false/)
  assert.match(app, /window\.__dshAppLifecycle = \{[\s\S]*setBackgrounded\(value\)/)
  assert.match(app, /function pauseRealtimeForBackground\(\)/)
  assert.match(app, /if \(!state\.token \|\| nativeBackgrounded\) return/)
  assert.match(app, /else pauseRealtimeForBackground\(\)/)
})

test('permission presets use the local command RPC instead of prompt text', () => {
  const localCommandStart = app.indexOf('async function runLocalSlashCommand')
  const localCommandEnd = app.indexOf('\nasync function runSlashCommand', localCommandStart)
  assert.ok(localCommandStart >= 0 && localCommandEnd > localCommandStart)
  const localCommand = app.slice(localCommandStart, localCommandEnd)
  assert.match(localCommand, /apiUrl\('\/api\/commands\/execute'\)/)
  assert.match(localCommand, /method: 'commands\/execute'/)
  assert.match(localCommand, /payload: \{ args: \{ agentId: state\.current, line: clean, images: \[\] \} \}/)

  const permissionStart = app.indexOf("const perm = e.target.closest('[data-perm]')")
  const permissionEnd = app.indexOf("const preset = e.target.closest('[data-preset]')", permissionStart)
  assert.ok(permissionStart >= 0 && permissionEnd > permissionStart)
  const permissionHandler = app.slice(permissionStart, permissionEnd)
  assert.match(permissionHandler, /void runSlashCommand\('\/permission ' \+ perm\.dataset\.perm\)/)
  assert.doesNotMatch(permissionHandler, /input\.value\s*=\s*'\/permission'/)
})

test('streamed reasoning updates nodes without rebuilding the history scroller', () => {
  assert.match(app, /function renderLiveReasoning\(\)/)
  assert.match(app, /data-reasoning-live/)
  assert.match(app, /text\.textContent !== next/)
  assert.match(app, /function scheduleReasoningRender\(\)[\s\S]*renderLiveReasoning\(\)/)
  const scheduleStart = app.indexOf('function scheduleReasoningRender()')
  const scheduleEnd = app.indexOf('\nfunction trimVisible()', scheduleStart)
  assert.ok(scheduleStart >= 0 && scheduleEnd > scheduleStart)
  assert.doesNotMatch(app.slice(scheduleStart, scheduleEnd), /renderHistory\(/)
  assert.match(app, /Repainting the overview[\s\S]*every streamed frame/)
})
