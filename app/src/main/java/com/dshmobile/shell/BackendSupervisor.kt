package com.dsharnessmobile.shell

/** The four user-visible stages of a local DSH for Android start. */
enum class BackendStage {
  INSTALL,
  ENGINE,
  GATEWAY,
  UI,
}

sealed interface BackendEvent {
  data class Started(val stage: BackendStage) : BackendEvent
  data class InstallProgress(val bytesDone: Long, val bytesTotal: Long) : BackendEvent
  data class Ready(val stage: BackendStage) : BackendEvent
}

sealed interface BackendResult {
  data class Ready(val uiEndpoint: String) : BackendResult
  data class Failed(val stage: BackendStage, val reason: String) : BackendResult
}

/**
 * Small, UI-free orchestration contract for the local backend.
 *
 * Each port owns exactly one layer. The supervisor only defines ordering:
 * install/runtime -> DSH engine -> local gateway -> UI handoff. It never
 * renders views, reads Android widgets, or reaches into gateway internals.
 */
interface InstallBackend {
  fun isReady(): Boolean
  fun prepare(onProgress: (Long, Long) -> Unit): Boolean
  fun finalize()
}

interface EngineBackend {
  fun isRunning(): Boolean
  fun start(): Boolean
}

interface GatewayBackend {
  fun isRunning(): Boolean
  fun start(): Boolean
  fun stop()
  fun uiEndpoint(): String
}

class BackendSupervisor(
  private val install: InstallBackend,
  private val engine: EngineBackend,
  private val gateway: GatewayBackend,
  private val attempts: Int = 31,
  private val waitMs: Long = 1_000L,
  private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

  fun start(emit: (BackendEvent) -> Unit = {}): BackendResult {
    emit(BackendEvent.Started(BackendStage.INSTALL))
    if (!install.isReady() && !install.prepare { done, total ->
        emit(BackendEvent.InstallProgress(done, total))
      }) {
      return BackendResult.Failed(BackendStage.INSTALL, "运行时安装或校验失败")
    }
    install.finalize()
    emit(BackendEvent.Ready(BackendStage.INSTALL))

    emit(BackendEvent.Started(BackendStage.ENGINE))
    if (!engine.isRunning() && !engine.start()) {
      return BackendResult.Failed(BackendStage.ENGINE, "DSH 后台进程启动失败")
    }
    if (!waitUntil(engine::isRunning)) {
      return BackendResult.Failed(BackendStage.ENGINE, "DSH Web 未在限定时间内就绪")
    }
    emit(BackendEvent.Ready(BackendStage.ENGINE))

    emit(BackendEvent.Started(BackendStage.GATEWAY))
    if (!gateway.isRunning() && !gateway.start()) {
      return BackendResult.Failed(BackendStage.GATEWAY, "Local Gateway 启动失败")
    }
    if (!waitUntil(gateway::isRunning)) {
      return BackendResult.Failed(BackendStage.GATEWAY, "Local Gateway 未在限定时间内就绪")
    }
    emit(BackendEvent.Ready(BackendStage.GATEWAY))

    emit(BackendEvent.Started(BackendStage.UI))
    emit(BackendEvent.Ready(BackendStage.UI))
    return BackendResult.Ready(gateway.uiEndpoint())
  }

  fun stopGateway() = gateway.stop()

  private fun waitUntil(probe: () -> Boolean): Boolean {
    val count = attempts.coerceAtLeast(1)
    repeat(count) { index ->
      if (probe()) return true
      if (index + 1 < count && waitMs > 0) sleep(waitMs)
    }
    return false
  }
}

/** Android adapter for the install/runtime layer. */
class AndroidInstallBackend(private val engineManager: EngineManager) : InstallBackend {
  override fun isReady(): Boolean = engineManager.snapshotFresh()

  override fun prepare(onProgress: (Long, Long) -> Unit): Boolean {
    val extracted = engineManager.refreshSnapshot(onProgress)
    return extracted
  }

  override fun finalize() = engineManager.deployUndoCli()
}

/** Android adapter for the DSH process layer. */
class AndroidEngineBackend(private val engineManager: EngineManager) : EngineBackend {
  override fun isRunning(): Boolean = EngineProbe.check().optBoolean("running", false)
  override fun start(): Boolean = engineManager.startEngine()
}

/** Android adapter for the local gateway process layer. */
class AndroidGatewayBackend(
  private val context: android.content.Context,
  private val engineManager: EngineManager,
) : GatewayBackend {
  override fun isRunning(): Boolean = GatewayProbe.check().optBoolean("running", false)

  override fun start(): Boolean = LocalGatewayManager.ensureRunning(context, engineManager)

  override fun stop() = LocalGatewayManager.stop()

  override fun uiEndpoint(): String {
    val token = LocalGatewayManager.accessToken(context)
    val suffix = if (token.isEmpty()) "" else "?token=" + android.net.Uri.encode(token) + "&local=1"
    return GatewayProbe.GATEWAY_URL + "/" + suffix
  }
}
