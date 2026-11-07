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
            // Recopy when the bundled list changed (app update) - the marker
            // records which asset version the file on disk came from. Without
            // this, installs updating to a newer list kept the stale copy.
            val marker = File(dir, "$FILE_NAME.version")
            val assetVersion = BuildConfig.VERSION_CODE.toString()
            val stale =
                !out.exists() || out.length() == 0L ||
                    runCatching { marker.readText().trim() }.getOrNull() != assetVersion
            if (stale) {
                runCatching {
                    context.assets.open("hosts.txt").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    marker.writeText(assetVersion)
                }
            }
        } else {
            out.delete()
        }
        Os.setenv("HOST_FILE", out.absolutePath, true)
    }
}
