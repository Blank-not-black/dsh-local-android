package com.dsharnessmobile.shell

import android.util.Log
import java.io.File

/**
 * Starts binaries from the relocated embedded runtime.
 *
 * Android versions that reject direct execution from app-private storage can
 * still start the same ELF through the system linker. This utility is shared
 * by the DSH Engine and Local Gateway so the two backend layers have identical
 * process-launch semantics without depending on each other's managers.
 */
object EmbeddedProcess {

  private const val TAG = "dsh-process"

  fun start(
    argv: List<String>,
    env: Map<String, String>,
    logFile: File,
  ): Process {
    fun build(command: List<String>): ProcessBuilder = ProcessBuilder(command).also { builder ->
      builder.environment().putAll(env)
      builder.redirectErrorStream(true)
      builder.redirectOutput(logFile)
    }

    return try {
      build(argv).start()
    } catch (error: java.io.IOException) {
      if (error.message?.contains("Permission denied") != true) throw error
      Log.w(TAG, "direct embedded process exec denied; retrying through linker64")
      build(listOf("/system/bin/linker64") + argv).start()
    }
  }
}
