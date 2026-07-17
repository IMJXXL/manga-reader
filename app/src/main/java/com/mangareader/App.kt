package com.mangareader

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mangareader.data.MangaDatabase
import com.mangareader.viewer.ArchiveEngine
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application() {
    val db by lazy { MangaDatabase.getDatabase(this) }
    val crashHandler by lazy { CrashHandler(this) }
    val engine by lazy { ArchiveEngine(this) }
    val imageLoader by lazy {
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes((Runtime.getRuntime().maxMemory() * 0.3).toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(5L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
    
    override fun onCreate() {
        super.onCreate()
        SignatureVerifier.verify(this, "")
        startBackgroundVerify()
        crashHandler.init()
        crashHandler.clearOldCrashLogs()
        startAnrWatchdog()
    }

    private fun startBackgroundVerify() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(45_000)
                    SignatureVerifier.periodicCheck(this, "")
                } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "SigVerifyBg"; start() }
    }

    private fun startAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        val isEink = getSharedPreferences("manga_reader", MODE_PRIVATE).getBoolean("eink_mode", false)
        val checkInterval = if (isEink) 5000L else 2000L
        val blockThreshold = if (isEink) 8000L else 3000L
        val watchdog = Thread {
            while (true) {
                try {
                    Thread.sleep(checkInterval)
                    val responded = java.util.concurrent.atomic.AtomicBoolean(false)
                    mainHandler.post { responded.set(true) }
                    Thread.sleep(blockThreshold)
                    if (!responded.get()) {
                        val stack = Looper.getMainLooper().thread.stackTrace
                        val trace = stack.joinToString("\n") { "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                        val msg = "=== 主线程卡顿 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ===\n$trace\n\n"
                        Log.e("AnrWatchdog", msg)
                        try {
                            val dir = getExternalFilesDir(null)
                            if (dir != null && !dir.exists()) dir.mkdirs()
                            val f = java.io.File(dir ?: return@Thread, "anr_log.txt")
                            f.appendText(msg)
                        } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) { break }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "AnrWatchdog"
        watchdog.start()
    }
}
