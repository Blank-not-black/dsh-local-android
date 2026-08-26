import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

test('minimal profile contains only the default Android boot surface', async () => {
  const profile = await readFile(
    path.join(repoRoot, 'runtime', 'minimal', 'cordis.patch.yml'),
    'utf8',
  )

  for (const required of [
    '@dsh-android/dsh-shell-termux',
    '@dsh-android/dsh-host-web-compat',
    '@dsh-android/dsh-client-ui-responsive',
    '@deepseek-ai/dsh-agent-default-model',
    '@deepseek-ai/dsh-client-ui-directory-picker-native',
  ]) {
    assert.match(profile, new RegExp(required.replaceAll('/', '\\/')))
  }

  for (const optional of [
    'dsh-android-bridge',
    'dsh-android-file-open',
    'dsh-android-linux-env',
    'dsh-android-manage',
    'dsh-attachment-formats',
    'dsh-undo-savepoint',
    'dshmarketplace-plugin',
  ]) {
    assert.doesNotMatch(profile, new RegExp(optional))
  }
})

test('minimal snapshot builder is reproducible and keeps the profile boundary', async () => {
  const script = await readFile(
    path.join(repoRoot, 'scripts', 'build-minimal-snapshot.sh'),
    'utf8',
  )

  assert.match(script, /tar -xJf/)
  assert.match(script, /tar --sort=name/)
  assert.match(script, /runtime\/minimal\/cordis\.patch\.yml/)
  assert.match(script, /profile_modules/)
  assert.match(script, /usr\/lib\/node_modules\/npm/)
})
