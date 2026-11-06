package me.ik.argon

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Experiment (prebuilt-compatible font route): declare @font-face entries for
 * the exact family names the engine's per-script fallback looks up, sourced
 * from the device's own /system/fonts files (world-readable), plus one
 * positive-control family used only by the instrumentation test page. No
 * default-family replacement: normal pages only see extra family
 * declarations, never overridden defaults.
 */
object FontCss {
    private const val TAG = "ArgonFont"
    private const val MAX_FONT_BYTES = 4 * 1024 * 1024

    private val candidates =
        listOf(
            Triple("Noto Sans Bengali", "NotoSansBengali-VF.ttf", 0),
            Triple("Noto Sans Devanagari", "NotoSansDevanagari-VF.ttf", 0),
            // Positive control: used ONLY by the test page via explicit
            // font-family, proving @font-face + data: URL fonts work at all.
            Triple("ArgonTestBengali", "NotoSansBengali-VF.ttf", 0),
        )

    /** Writes filesDir/argon-user.css; returns its path, or null if no usable fonts. */
    fun write(ctx: Context): String? {
        val sb = StringBuilder()
        val report = StringBuilder()
        for ((family, file, _) in candidates) {
            try {
                val f = File("/system/fonts/$file")
                if (!f.exists() || f.length() <= 0 || f.length() > MAX_FONT_BYTES) {
                    report.append("SKIP $family ($file missing=${!f.exists()} size=${if (f.exists()) f.length() else -1}); ")
                    continue
                }
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                sb.append("@font-face{font-family:'")
                    .append(family)
                    .append("';src:url(data:font/ttf;base64,")
                    .append(b64)
                    .append(") format('truetype');font-weight:100 900;font-style:normal;}\n")
                report.append("OK $family <- $file ${f.length()}B; ")
            } catch (t: Throwable) {
                report.append("ERR $family ${t.message}; ")
            }
        }
        if (sb.isEmpty()) {
            Log.i(TAG, "FONT_CSS_REPORT none written: $report")
            return null
        }
        val out = File(ctx.filesDir, "argon-user.css")
        out.writeText(sb.toString())
        Log.i(TAG, "FONT_CSS_REPORT cssBytes=${out.length()} path=${out.absolutePath} :: $report")
        return out.absolutePath
    }
}
