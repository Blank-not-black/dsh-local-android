import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repo = fileURLToPath(new URL('..', import.meta.url))
const gateway = join(repo, 'gateway', 'gateway.js')

async function waitForHealth(port, child) {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/health`)
      if (res.ok) return await res.json()
    } catch {}
    if (child.exitCode !== null) break
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error('local gateway did not become healthy')
}

test('local gateway exposes local capability and protects API', async () => {
  const root = mkdtempSync(join(tmpdir(), 'dsh-local-gateway-'))
  const port = 18787 + Math.floor(Math.random() * 300)
  const token = 'local-test-token-123456789'
  const child = spawn(process.execPath, [gateway], {
    cwd: join(repo, 'gateway'),
    env: {
      ...process.env,
      HOME: root,
      DSH_REMOTE_LOCAL: '1',
      HOST: '127.0.0.1',
      PORT: String(port),
      TOKEN: token,
      DSH_UPSTREAM: 'http://127.0.0.1:9',
      DSH_REMOTE_FS_ROOT: root,
      DSH_REMOTE_ANNOUNCEMENTS_URL: '',
      DSH_REMOTE_UPDATE_CHECK: '0',
    },
    stdio: 'ignore',
  })
  try {
    const health = await waitForHealth(port, child)
    assert.equal(health.capabilities.localMode, 1)
    assert.equal(health.upstreamOk, false)

    const page = await fetch(`http://127.0.0.1:${port}/?token=${token}&local=1`)
    assert.equal(page.status, 200)
    const html = await page.text()
    assert.match(html, /settings-group-servers/)

    const unauthorized = await fetch(`http://127.0.0.1:${port}/api/session.list`, {
      headers: { authorization: 'Bearer invalid-token' },
    })
    assert.equal(unauthorized.status, 401)
  } finally {
    if (child.exitCode === null) {
      child.kill('SIGTERM')
      await new Promise(resolve => child.once('exit', resolve))
    }
    rmSync(root, { recursive: true, force: true })
  }
})
