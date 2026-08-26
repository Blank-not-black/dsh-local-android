package com.dsharnessmobile.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the dsh-Remote gateway beside the embedded DSH web engine. */
object LocalGatewayManager {

  private const val TAG = "dsh-local-gateway"
  private const val GATEWAY_DIR_NAME = "dsh-local-gateway"
  private const val TOKEN_NAME = "token"
  private val starting = AtomicBoolean(false)

  @Volatile
  private var process: Process? = null

  private fun root(context: Context): File = File(context.filesDir, GATEWAY_DIR_NAME)
  private fun tokenFile(context: Context): File = File(root(context), TOKEN_NAME)

  fun logFile(context: Context): File = File(root(context), "gateway.log")

  fun accessToken(context: Context): String = try {
    tokenFile(context).takeIf { it.exists() }?.readText()?.trim().orEmpty()
  } catch (_: Exception) {
    ""
  }

  fun ensureRunning(context: Context, engineManager: EngineManager): Boolean {
    if (GatewayProbe.check(250).optBoolean("running", false)) return true
    if (!engineManager.engineReady) return false
    if (!starting.compareAndSet(false, true)) return true
    return try {
      val dir = root(context).apply { mkdirs() }
      deployAssets(context, dir)
      val node = File(engineManager.usrDir, "bin/node")
      val script = File(dir, "gateway.js")
      if (!node.exists() || !script.exists()) {
        Log.e(TAG, "gateway start skipped: node or gateway.js missing")
        false
      } else {
        process?.let { old -> try { old.destroy() } catch (_: Throwable) {} }
        val log = logFile(context)
        val env = engineManager.shellEnv().toMutableMap()
        env["HOST"] = "127.0.0.1"
        env["PORT"] = "8787"
        env["TOKEN_FILE"] = tokenFile(context).absolutePath
        env["DSH_UPSTREAM"] = EngineProbe.ENGINE_URL
        env["DSH_REMOTE_LOCAL"] = "1"
        env["DSH_REMOTE_FS_ROOT"] = engineManager.homeDir.absolutePath
        env["DSH_REMOTE_ANNOUNCEMENTS_URL"] = ""
        env["DSH_REMOTE_UPDATE_CHECK"] = "0"
        process = EmbeddedProcess.start(
          listOf(node.absolutePath, script.absolutePath),
          env,
          log,
        )
        LogCollector.log(TAG, "local gateway started on ${GatewayProbe.GATEWAY_URL}")
        true
      }
    } catch (t: Throwable) {
      Log.e(TAG, "local gateway start failed", t)
      try {
        logFile(context).appendText(
          "[local-gateway] start FAILED: " + (t.message ?: t.javaClass.simpleName) + "\n",
        )
      } catch (_: Throwable) {
      }
      LogCollector.log(TAG, "local gateway start FAILED: " + (t.message ?: t.javaClass.simpleName))
      false
    } finally {
      starting.set(false)
    }
  }

  fun stop() {
    process?.let { try { it.destroy() } catch (_: Throwable) {} }
    process = null
    LogCollector.log(TAG, "local gateway stopped")
  }

  private fun deployAssets(context: Context, dir: File) {
    copyAsset(context, "gateway.js", File(dir, "gateway.js"))
    copyAsset(context, "gateway-stats.cjs", File(dir, "gateway-stats.cjs"))
    copyAssetTree(context, "public", File(dir, "public"))
  }

  private fun copyAsset(context: Context, asset: String, target: File) {
    val bytes = context.assets.open(asset).use { it.readBytes() }
    if (target.exists() && target.readBytes().contentEquals(bytes)) return
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
  }

  private fun copyAssetTree(context: Context, asset: String, target: File) {
    val children = context.assets.list(asset).orEmpty()
    if (children.isEmpty()) {
      copyAsset(context, asset, target)
      return
    }
    target.mkdirs()
    for (child in children) copyAssetTree(context, "$asset/$child", File(target, child))
  }
}
