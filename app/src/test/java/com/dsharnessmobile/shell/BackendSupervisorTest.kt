package com.dsharnessmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSupervisorTest {

  @Test
  fun installFailureStopsBeforeEngine() {
    val install = FakeInstall(ready = false, prepareResult = false)
    val engine = FakeEngine()
    val gateway = FakeGateway()
    val result = supervisor(install, engine, gateway).start()

    assertEquals(BackendResult.Failed(BackendStage.INSTALL, "运行时安装或校验失败"), result)
    assertFalse(engine.startCalled)
    assertFalse(gateway.startCalled)
  }

  @Test
  fun engineFailureStopsBeforeGateway() {
    val install = FakeInstall(ready = true)
    val engine = FakeEngine(startResult = false)
    val gateway = FakeGateway()
    val result = supervisor(install, engine, gateway).start()

    assertEquals(BackendResult.Failed(BackendStage.ENGINE, "DSH 后台进程启动失败"), result)
    assertTrue(engine.startCalled)
    assertFalse(gateway.startCalled)
  }

  @Test
  fun gatewayFailureDoesNotHandOffToUi() {
    val install = FakeInstall(ready = true)
    val engine = FakeEngine(running = true)
    val gateway = FakeGateway(startResult = false)
    val events = mutableListOf<BackendEvent>()
    val result = supervisor(install, engine, gateway).start(events::add)

    assertEquals(BackendResult.Failed(BackendStage.GATEWAY, "Local Gateway 启动失败"), result)
    assertTrue(events.none { it == BackendEvent.Ready(BackendStage.UI) })
  }

  @Test
  fun readyHandoffHasFourOrderedStages() {
    val events = mutableListOf<BackendEvent>()
    val result = supervisor(
      FakeInstall(ready = true),
      FakeEngine(running = true),
      FakeGateway(running = true),
    ).start(events::add)

    assertEquals(BackendResult.Ready("fake://local-ui"), result)
    assertEquals(
      listOf(
        BackendEvent.Started(BackendStage.INSTALL),
        BackendEvent.Ready(BackendStage.INSTALL),
        BackendEvent.Started(BackendStage.ENGINE),
        BackendEvent.Ready(BackendStage.ENGINE),
        BackendEvent.Started(BackendStage.GATEWAY),
        BackendEvent.Ready(BackendStage.GATEWAY),
        BackendEvent.Started(BackendStage.UI),
        BackendEvent.Ready(BackendStage.UI),
      ),
      events,
    )
  }

  private fun supervisor(
    install: FakeInstall,
    engine: FakeEngine,
    gateway: FakeGateway,
  ) = BackendSupervisor(install, engine, gateway, attempts = 1, waitMs = 0)

  private class FakeInstall(
    private val ready: Boolean,
    private val prepareResult: Boolean = true,
  ) : InstallBackend {
    override fun isReady(): Boolean = ready
    override fun prepare(onProgress: (Long, Long) -> Unit): Boolean {
      onProgress(10, 20)
      return prepareResult
    }
    override fun finalize() = Unit
  }

  private class FakeEngine(
    private var running: Boolean = false,
    private val startResult: Boolean = true,
  ) : EngineBackend {
    var startCalled = false
    override fun isRunning(): Boolean = running
    override fun start(): Boolean {
      startCalled = true
      running = startResult
      return startResult
    }
  }

  private class FakeGateway(
    private var running: Boolean = false,
    private val startResult: Boolean = true,
  ) : GatewayBackend {
    var startCalled = false
    override fun isRunning(): Boolean = running
    override fun start(): Boolean {
      startCalled = true
      running = startResult
      return startResult
    }
    override fun stop() = Unit
    override fun uiEndpoint(): String = "fake://local-ui"
  }
}
