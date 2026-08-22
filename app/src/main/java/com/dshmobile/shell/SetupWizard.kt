package com.dsharnessmobile.shell

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 初始配置向导（0.13.0 PRD F1.0，M3.2）：五步状态机（欢迎 → 运行时就绪 → 工具链就绪+镜像 →
 * 共享文件夹 → 完成）；步骤可回退/跳过/重进；状态持久化（prefs），重进只提示增量；完成写 profileInstalled 标记。
 * 安卓调试桥授权不在向导展示（风险状态，入口藏开发者选项——由 AdbState 面板承托）。
 * 与「工具与环境」设置页共享同一后端（dsh-android-linux-env 插件）；本向导为壳侧首启 UX 层。
 */
class SetupWizard(private val activity: Activity) {

  companion object {
    private const val PREFS = "dsh-setup"
    private const val KEY_STEP = "wizard.step" // -1 未开始 / 0..4 当前步 / 5 完成
    private const val KEY_MIRROR = "wizard.mirror"

    fun stepOf(context: Context): Int =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STEP, -1)

    fun isDone(context: Context): Boolean = stepOf(context) >= 5

    fun setStep(context: Context, step: Int) {
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putInt(KEY_STEP, step).apply()
    }

    fun mirror(context: Context): String =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MIRROR, "官方优先") ?: "官方优先"

    fun setMirror(context: Context, m: String) {
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_MIRROR, m).apply()
    }
  }

  /** 向导根视图；onDone 回调完成后由宿主切换回正常启动流。 */
  fun build(onDone: () -> Unit, onPickDir: () -> Unit): View {
    val scroll = ScrollView(activity)
    val root = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(48), dp(24), dp(24))
    }
    scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val title = TextView(activity).apply {
      text = "初始化向导"
      textSize = 26f
      setTextColor(Color.WHITE)
    }
    root.addView(title)
    val sub = TextView(activity).apply {
      text = buildString {
        append("步骤 ")
        append(stepString())
      }
      textSize = 15f
      setTextColor(Color.LTGRAY)
    }
    root.addView(sub)

    val body = TextView(activity).apply {
      text = stepBody()
      textSize = 16f
      setLineSpacing(0f, 1.25f)
      setTextColor(Color.WHITE)
    }
    root.addView(body)

    val btnRow = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
    }
    root.addView(btnRow)

    fun button(label: String, onClick: () -> Unit): Button {
      val b = Button(activity).apply {
        text = label
        setTextColor(Color.parseColor("#0B0F14"))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
      }
      return b
    }

    val prevBtn = button("上一步") {
      val cur = stepOf(activity)
      if (cur > 0) {
        setStep(activity, cur - 1)
        activity.recreate()
      }
    }
    prevBtn.visibility = if (stepOf(activity) > 0) View.VISIBLE else View.GONE
    btnRow.addView(prevBtn)

    val nextBtn = button(stepForwards()) {
      val cur = stepOf(activity)
      when (cur) {
        2 -> { // 共享目录：调 SAF 选择器（复用现有桥回调链）
          setStep(activity, 3)
          onPickDir()
        }
        else -> {
          if (cur >= 4) {
            setStep(activity, 5)
            onDone()
          } else {
            setStep(activity, cur + 1)
            activity.recreate()
          }
        }
      }
    }
    btnRow.addView(nextBtn)
    if (stepOf(activity) < 0) setStep(activity, 0) // 首启进入
    return scroll
  }

  private fun stepForwards(): String = when (stepOf(activity)) {
    0 -> "开始"
    1, 2 -> "下一步"
    3 -> "完成向导"
    else -> "进入"
  }

  private fun stepString(): String = when (stepOf(activity)) {
    0 -> "1/5 · 欢迎与隐私"
    1 -> "2/5 · 运行时就绪"
    2 -> "3/5 · 工具链与镜像"
    3 -> "4/5 · 共享文件夹"
    else -> "5/5 · 完成"
  }

  private fun stepBody(): String = when (stepOf(activity)) {
    0 -> "欢迎使用 DeepCode（dsh-mobile）。本应用内嵌 Termux 运行时与 AI 引擎，无需外部环境。\n\n隐私：运行数据保存在应用私有目录；模型请求经你配置的凭据发送。"
    1 -> "正在确认内嵌运行时（解压与引擎探测）……请稍候。若长时间停留，请查看主界面的运行状态。"
    2 -> "工具链就绪状态可在「工具与环境」页查看（预装 python/perl/ruby/ripgrep/vim 等，dpkg 数据库已初始化）。\n\n软件源镜像（默认：国内优先，逐级回退官方）——当前：${mirror(activity)}"
    3 -> "选择共享文件夹：AI 与 shell 工具将可读写你选定的目录（写面默认仅工作区与选定共享目录）。"
    else -> "配置完成。点击「进入」开始使用。\n\n提示：安卓调试桥授权入口位于「开发者选项 → 安全」（默认不可见，属高风险状态）。"
  }

  private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
