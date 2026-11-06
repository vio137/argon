package me.ik.argon

import android.content.Context
import android.system.Os
import java.io.File

object Blocklist {
    const val DOMAIN_COUNT = 77759
    private const val FILE_NAME = "android_hosts"

    fun apply(context: Context, enabled: Boolean) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, FILE_NAME)
        if (enabled) {
            if (!out.exists() || out.length() == 0L) {
                runCatching {
                    context.assets.open("hosts.txt").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        } else {
            out.delete()
        }
        Os.setenv("HOST_FILE", out.absolutePath, true)
    }
}
