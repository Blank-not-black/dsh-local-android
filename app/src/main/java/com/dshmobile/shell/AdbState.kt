package com.dsharnessmobile.shell

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject

/**
 * ADB 授权状态单一事实来源（0.13.0 F1.7）：三道授权人门的门内状态与门控判定。
 * - 门1 系统无线调试：应用不可程序化开启；本应用只记录"曾配对"痕迹（系统侧状态不可读，
 *   配对成功即间接证明无线调试开启）。
 * - 门2 应用内「允许访问」开关：显式持久化，默认关闭；回收即通道失败关闭。
 * - 门3 配对码：用户输入后写入 paired 状态；重启后需重新配对（视为安全特性）。
 * - 前置门控：完全访问档位（fullAccess = All Files Access 已授予）——自动审批模式不构成开放条件。
 * 本版为状态面 + 失败关闭语义：真正执行随壳侧 ADB 客户端（配对/连接自本机调试守护进程）就绪后接入。
 */
object AdbState {

  private const val PREFS = "dsh-adb"
  private const val KEY_ALLOW = "allowSwitch"
  private const val KEY_PAIRED = "paired"

  fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun allowSwitch(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW, false)

  fun setAllowSwitch(context: Context, enable: Boolean) {
    prefs(context).edit().putBoolean(KEY_ALLOW, enable).apply()
  }

  fun paired(context: Context): Boolean = prefs(context).getBoolean(KEY_PAIRED, false)

  fun setPaired(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_PAIRED, value).apply()
  }

  /** 完全访问档位（通道前置门控；API 30+ 的 All Files Access）。 */
  fun fullAccess(): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    return Environment.isExternalStorageManager()
  }

  /** 门控判定：完全访问档位 + 开关 + 配对 全部满足（自动审批模式不构成开放条件——本函数不读审批模式）。 */
  fun authorized(context: Context): Boolean = fullAccess() && allowSwitch(context) && paired(context)

  /** 状态 JSON（桥 getAdbState / 探活消费）。 */
  fun stateJson(context: Context): String {
    val allow = allowSwitch(context)
    val pair = paired(context)
    val full = fullAccess()
    val authorized = full && allow && pair
    val message = when {
      authorized -> null
      !full -> "未授权：未处于完全访问档位（自动审批模式不构成开放条件）；请先在设置中授予「所有文件访问」"
      !allow -> "未授权：应用内「允许访问」开关未开启（开发者选项→安全）"
      else -> "未授权：未配对——请在开发者选项开启无线调试并在此输入配对码（重启后需重新配对）"
    }
    return JSONObject()
      .put("tier", if (authorized) "T1" else "T0")
      .put("fullAccess", full)
      .put("allowSwitch", allow)
      .put("paired", pair)
      .put("wirelessDebugOn", pair)
      .put("authorized", authorized)
      .put("message", message)
      .toString()
  }

  /** 引擎环境注入（bridge 插件 currentStatus 读取）。 */
  fun env(context: Context): Map<String, String> = mapOf(
    "DSH_ADB_ALLOW" to if (allowSwitch(context)) "1" else "0",
    "DSH_ADB_PAIRED" to if (paired(context)) "1" else "0",
    "DSH_ADB_WIRELESS" to if (paired(context)) "1" else "0",
  )

  /** adbShell 执行原语（本版失败关闭：未授权/未实现执行通道一律拒绝并给引导）。 */
  fun adbShellExecute(context: Context, cmd: String): String {
    if (!authorized(context)) {
      return JSONObject()
        .put("ok", false)
        .put("guidance", "未授权：请完成授权（完全访问档位 → 允许访问开关 → 配对码）后再调用 ADB 通道")
        .toString()
    }
    // T1 执行通道（壳侧 ADB 客户端连接本机调试守护进程）为后续接入面：
    // 本版在授权状态下仍返回"通道接入中"（绝不静默降级到非授权路径）。
    return JSONObject()
      .put("ok", false)
      .put("guidance", "ADB 执行通道接入中（授权状态已满足；桥执行原语待壳侧客户端就绪）")
      .toString()
  }
}
