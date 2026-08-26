import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const engineManager = await readFile(
  path.join(repoRoot, 'app', 'src', 'main', 'java', 'com', 'dshmobile', 'shell', 'EngineManager.kt'),
  'utf8',
)
const mainActivity = await readFile(
  path.join(repoRoot, 'app', 'src', 'main', 'java', 'com', 'dshmobile', 'shell', 'MainActivity.kt'),
  'utf8',
)

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

  for (const disabled of ['bash-sandbox', 'ui-layout', 'agent-default-model', 'directory-picker']) {
    assert.match(profile, new RegExp(`id: ${disabled}\\n  disabled: true`))
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
  assert.match(script, /THIRD_PARTY_NOTICES\.md/)
  assert.match(script, /TERMUX-LICENSE\.md/)
  assert.match(script, /copyright\.\*/)
  assert.match(script, /snapshot\.runtime\.sha256/)
  assert.match(script, /-path "\$stage_dir\/usr\/share\/LICENSES"/)
  assert.match(script, /-path "\$stage_dir\/usr\/share\/doc"/)
  assert.match(script, /find "\$stage_dir\/usr" "\$stage_dir\/home\/\.dsh\/profiles"/)
  assert.doesNotMatch(script, /usr\/share\/doc \\\n/)
})

test('snapshot freshness separates runtime identity from license notices', async () => {
  const runtimeFingerprint = await readFile(
    path.join(repoRoot, 'app', 'src', 'main', 'assets', 'snapshot.runtime.sha256'),
    'utf8',
  )
  assert.match(runtimeFingerprint.trim(), /^[0-9a-f]{64}$/)
  assert.match(engineManager, /snapshot\.runtime\.sha256/)
  assert.match(engineManager, /\.snapshot-runtime-fingerprint/)
  assert.match(engineManager, /runtime license notices updated/)
  assert.match(engineManager, /snapshot fingerprint migrated to runtime-only identity/)
  assert.match(engineManager, /legacy snapshot migrated without runtime re-extraction/)
  assert.match(engineManager, /File\(homeDir, "\.dsh\/profiles"\)/)
  assert.match(engineManager, /uncompressed tar bytes/)
  assert.match(engineManager, /snapshot\.tar\.xz"\), 0L/)
  assert.match(mainActivity, /progressText\.text = "正在写入内嵌运行时…"/)
  assert.doesNotMatch(mainActivity, /val mb = event\.bytesDone/)
})
