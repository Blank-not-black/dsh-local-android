package com.dsharnessmobile.shell

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Probes the local dsh-Remote gateway that fronts the embedded DSH engine. */
object GatewayProbe {

  const val GATEWAY_URL = "http://127.0.0.1:8787"

  /** One-shot loopback health probe. */
  fun check(timeoutMs: Int = 800): JSONObject {
    return try {
      val conn = URL(GATEWAY_URL + "/health?probe=live").openConnection() as HttpURLConnection
      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      conn.requestMethod = "GET"
      val start = System.currentTimeMillis()
      val code = conn.responseCode
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      conn.disconnect()
      val result = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
      result.put("running", code in 200..299)
        .put("latencyMs", System.currentTimeMillis() - start)
    } catch (e: Exception) {
      JSONObject().put("running", false).put("error", e.message ?: "unknown")
    }
  }
}
