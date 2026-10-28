package com.geoenv.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 启动崩溃诊断：在 Application.onCreate 最早期安装未捕获异常处理器，
 * 把完整堆栈同步写入公共下载目录（MediaStore.Downloads，无需任何存储权限），
 * 便于在无法读取 logcat 的环境下离线定位"一打开就闪退"的真实根因。
 *
 * 写入完成后仍交回原处理器，App 行为不变（依旧会崩溃），仅额外留档。
 */
object CrashLogger {

    private const val TAG = "GeoEnvCrash"
    private const val FILE_NAME = "geoenv_crash.txt"

    /** 必须在 super.onCreate()（Hilt 注入）之前调用，才能捕获其抛出的异常。 */
    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 尽力留档，任何一步失败都不影响后续崩溃流程
            runCatching {
                val text = format(thread, throwable)
                Log.e(TAG, text)
                write(appContext, text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun format(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("GeoEnv startup crash")
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("thread=${thread.name} pid=${android.os.Process.myPid()}")
            appendLine("---- stacktrace ----")
            append(sw.toString())
        }
    }

    private fun write(context: Context, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("insert failed")
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(bytes)
                    it.flush()
                } ?: error("openOutputStream failed")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                return
            }
        }

        // 回退：直接写公共目录（低版本或 MediaStore 失败时尝试）
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).writeBytes(bytes)
            }
        }

        // 兜底：写应用私有外部目录（至少保证堆栈被持久化，即便本次读不到）
        runCatching {
            context.getExternalFilesDir(null)?.let { dir ->
                File(dir, FILE_NAME).writeBytes(bytes)
            }
        }
    }
}
