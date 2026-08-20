package com.dsharnessmobile.shell

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * Built-in console: a WebView loads assets/console.html (terminal-style UI) while
 * ConsoleSession spawns the snapshot bash (env matching the engine) → commands via the stdin pipe,
 * output returned to the UI through the consoleBridge JS interface. Works even when the engine is
 * down (diagnostics scenarios).
 */
class ConsoleActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private val session = ConsoleSession(this)
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private var sessionStarted = false

  /** Last status text (re-pushed on onPageFinished; replays status lost before page load). */
  private var lastStatus: String? = null

  /** Push status (main thread only). */
  private fun pushStatus(text: String) {
    webView.evaluateJavascript("window.__consoleStatus(" + jsString(text) + ")", null)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    webView = WebView(this).apply {
      id = View.generateViewId()
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
      )
    }
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        forceDark = WebSettings.FORCE_DARK_AUTO
      }
    }
    // Re-push status after page load: bash may be ready in onStart while console.html's
    // JS bridge is defined later — early evaluateJavascript calls are silently dropped.
    webView.webViewClient = object : android.webkit.WebViewClient() {
      override fun onPageFinished(view: android.webkit.WebView, url: String) {
        super.onPageFinished(view, url)
        lastStatus?.let { pushStatus(it) }
      }
    }
    webView.addJavascriptInterface(ConsoleBridge(), "consoleBridge")
    setContentView(webView)
    webView.loadUrl("file:///android_asset/console.html")
  }

  override fun onStart() {
    super.onStart()
    if (sessionStarted) return
    sessionStarted = session.start(object : ConsoleSession.Listener {
      override fun onOutput(text: String) {
        handler.post {
          webView.evaluateJavascript("window.__consoleAppend(" + jsString(text) + ")", null)
        }
      }

      override fun onStatus(text: String) {
        lastStatus = text
        handler.post { pushStatus(text) }
      }

      override fun onExit(code: Int) {
        handler.post {
          webView.evaluateJavascript(
            "window.__consoleStatus(" + jsString("bash 已退出（code $code）") + ")", null,
          )
        }
      }
    })
  }

  override fun onDestroy() {
    session.destroy()
    webView.destroy()
    super.onDestroy()
  }

  /** JS bridge: command submission + engine status query. */
  inner class ConsoleBridge {
    @JavascriptInterface
    fun submit(command: String) {
      session.writeCommand(command)
    }

    @JavascriptInterface
    fun engineStatus(): String = EngineProbe.check().toString()
  }
}
