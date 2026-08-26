import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const html = await readFile(new URL('../gateway/public/index.html', import.meta.url), 'utf8')
const manifest = JSON.parse(await readFile(new URL('../gateway/public/manifest.webmanifest', import.meta.url), 'utf8'))
const gatewayVersion = JSON.parse(await readFile(new URL('../gateway/public/version.json', import.meta.url), 'utf8'))
const gradle = await readFile(new URL('../app/build.gradle.kts', import.meta.url), 'utf8')
const strings = await readFile(new URL('../app/src/main/res/values/strings.xml', import.meta.url), 'utf8')

test('Android app and local WebView use the DSH for Android identity', () => {
  assert.match(html, /<title>DSH for Android<\/title>/)
  assert.match(html, /class="brand-name">DSH for Android<\/span>/)
  assert.equal(manifest.name, 'DSH for Android')
  assert.equal(manifest.short_name, 'DSH for Android')
  assert.match(strings, /<string name="app_name">DSH for Android<\/string>/)
})

test('Android and gateway versions are pinned to the first release candidate', () => {
  assert.equal(gatewayVersion.version, '0.1.0-rc.1')
  assert.match(gradle, /versionName = "0\.1\.0-rc\.1" \+ snapshotSuffix/)
  assert.match(gradle, /versionCode = 25/)
})
