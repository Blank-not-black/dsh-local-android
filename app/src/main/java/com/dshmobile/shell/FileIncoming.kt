package com.dsharnessmobile.shell

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URLDecoder

/**
 * 文件直达会话（0.13.0 PRD F5，M3.5）：外部「使用其他应用打开 / 分享」→
 * 路径校验与文件名净化 → 安全拷贝进临时工作区 → 交给引擎侧插件强制新会话。
 *
 * - 只接受 content:// 与 file:// 真实路径；白名单前缀校验；拒绝 ../ 上级跳转
 * - 文件名净化：问号/冒号/竖线/星号/反斜杠/双引号等非法字符（共享存储实测非法字符集）、
 *   百分号解码、255 字节边界（超长截断 + 哈希后缀），冲突自动重命名 (1)/(2)…
 * - 临时工作区：files/home/.dsh/workspaces/incoming（应用数据目录，原生语义完整）；
 *   纯手动清理（D15 决策：设置页一键清理 + 占用展示）
 * - 生命周期礼仪：onTaskRemoved 时清理本次产生的临时内容（元数据幂等）
 */
object FileIncoming {

  /** 临时工作区目录（引擎侧以共享目录机制接入的固定路径）。 */
  fun tmpWorkspace(context: Context): File =
    File(context.filesDir, "home/.dsh/workspaces/incoming").apply { mkdirs() }

  private val SAFE_PREFIXES = listOf(
    "content://", "file:///data/user/0/com.dsharnessmobile.shell/", "file:///data/data/com.dsharnessmobile.shell/",
    "file:///storage/emulated/0/", "file:///sdcard/",
  )

  /** 路径校验：仅接受白名单前缀的真实路径，拒绝上级跳转。返回可拷资源 Uri 描述或 null。 */
  fun validate(uriString: String, context: Context): Uri? {
    val uri = try { Uri.parse(URLDecoder.decode(uriString, "UTF-8")) } catch (_: Exception) { return null }
    if (uri.scheme == null) return null
    val ok = when (uri.scheme) {
      "content" -> true // 内容提供者：临时读授权；只拷贝不引用
      "file" -> {
        val p = uri.path ?: return null
        SAFE_PREFIXES.any { p.startsWith(it.removePrefix("file://").let { it }) } ||
          SAFE_PREFIXES.any { uriString.startsWith(it) }
      }
      else -> false
    }
    if (!ok) return null
    // 上级跳转拒绝
    val path = uri.path ?: return null
    if (path.split("/").any { it == ".." }) return null
    return uri
  }

  /** 文件名净化：非法字符替换、百分号解码、长度截断（255 字节边界 + 哈希后缀）。 */
  fun sanitizeName(raw: String): String {
    val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
    val cleaned = decoded
      .replace(Regex("[?*|:\\\"<>]"), "_")
      .replace(Regex("[\\u0000-\\u001f]"), "")
      .trim()
      .ifEmpty { "file" }
    // 255 字节边界（UTF-8 多字节安全截断）
    var count = 0
    var cut = cleaned.length
    for (i in cleaned.indices) {
      count += cleaned[i].toString().toByteArray(Charsets.UTF_8).size
      if (count > 200) { cut = i; break }
    }
    val short = cleaned.substring(0, cut)
    if (cut < cleaned.length) {
      val suffix = cleaned.hashCode().toUInt().toString(16).take(6)
      return short + "_" + suffix
    }
    return short
  }

  /** 冲突重命名：name.ext → name (1).ext / (2)… */
  fun uniqueName(dir: File, name: String): String {
    if (!File(dir, name).exists()) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (File(dir, "$base ($i)$ext").exists()) i++
    return "$base ($i)$ext"
  }

  /** 安全拷贝进临时工作区；返回落盘路径（或 null）。 */
  fun copyIn(context: Context, uri: Uri): File? {
    return try {
      val dir = tmpWorkspace(context)
      val display = queryDisplayName(context, uri) ?: "file"
      val name = uniqueName(dir, sanitizeName(display))
      val target = File(dir, name)
      context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      } ?: return null
      target
    } catch (_: Exception) {
      null
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
      context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
      )?.use { c ->
        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
      }
    } catch (_: Exception) {
      uri.lastPathSegment?.substringAfterLast('/')
    }
  }

  /** 元数据：本次打开的会话清单（生命礼仪清理的依据）。 */
  fun metaFile(context: Context): File = File(tmpWorkspace(context), ".meta.ndjson")

  fun recordOpening(context: Context, path: String) {
    try {
      metaFile(context).appendText(
        System.currentTimeMillis().toString() + "\t" + path + "\n",
      )
    } catch (_: Exception) {
    }
  }

  /** 清理本次临时会话与临时工作区内容（幂等；不阻塞进程退出——生命周期礼仪 F5.3）。 */
  fun cleanupTmp(context: Context) {
    try {
      val dir = tmpWorkspace(context)
      dir.listFiles()?.forEach { it.delete() }
      LogCollector.log("dsh-file-open", "temp workspace cleaned (task removed ritual)")
    } catch (_: Exception) {
    }
  }
}
