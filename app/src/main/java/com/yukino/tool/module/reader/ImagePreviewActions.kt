package com.yukino.tool.module.reader

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 图片预览动作: 保存到相册/分享/设壁纸, 三者共用当前预览位图(所见即所得,
// SVG 栅格图与 GIF 首帧同样可存)。全部同步实现, 调用方放 Dispatchers.IO 执行;
// 返回值为 Toast 文案: saveToGallery 必返回, share/setWallpaper 返回 null 表示
// 已进入系统流程(分享面板/裁剪页拉起), 无需提示。
// 预览位图在 26+ 是硬件位图(GPU 侧), 写盘/壁纸前先转回软件位图
object ImagePreviewActions {

    private fun softOf(bitmap: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else bitmap

    // 保存 PNG 到相册 Pictures/YukinoTool/: 29+ 走 MediaStore RELATIVE_PATH,
    // 28- 直写公共目录(需 WRITE_EXTERNAL_STORAGE, 未授权时提示)后扫库入库
    fun saveToGallery(context: Context, bitmap: Bitmap): String {
        if (Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) return "保存失败: 请先在系统设置中授予存储权限"
        val bmp = softOf(bitmap)
        val name = "preview_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/YukinoTool")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return "保存失败"
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                } ?: return "保存失败"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "YukinoTool"
                )
                if (!dir.exists() && !dir.mkdirs()) return "保存失败: 无法创建目录"
                val out = File(dir, name)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(
                    context, arrayOf(out.absolutePath), arrayOf("image/png"), null
                )
            }
            "已保存到相册 Pictures/YukinoTool"
        } catch (e: Exception) {
            "保存失败: ${e.message ?: "未知错误"}"
        }
    }

    // 分享: PNG 落 cache/export/(FileProvider 已有该路径映射)经 ACTION_SEND 拉起系统分享
    fun share(context: Context, bitmap: Bitmap): String? {
        return try {
            val bmp = softOf(bitmap)
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, "share_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileProvider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享图片").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            null
        } catch (e: Exception) {
            "分享失败: ${e.message ?: "未知错误"}"
        }
    }

    // 设壁纸: 位图落 cache/export/ 取 content uri, 优先系统裁剪页(用户可选区域);
    // 系统无裁剪组件(或拉起失败)时降级 setBitmap 直接设主屏壁纸
    fun setWallpaper(context: Context, bitmap: Bitmap): String? {
        val wm = WallpaperManager.getInstance(context)
        val bmp = softOf(bitmap)
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "wallpaper_${System.currentTimeMillis()}.png")
        return try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileProvider", file
            )
            val crop = runCatching {
                wm.getCropAndSetWallpaperIntent(uri)?.apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrNull()
            if (crop != null) {
                context.startActivity(crop)
                null
            } else {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                "壁纸已设置"
            }
        } catch (e: Exception) {
            "设置壁纸失败: ${e.message ?: "未知错误"}"
        }
    }
}
