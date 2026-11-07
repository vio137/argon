package me.ik.argon

import android.content.Context
import java.io.File

object StartPage {
    fun write(context: Context, bookmarks: List<Bookmark>, history: List<HistoryEntry>, engine: SearchEngine): String {
        // Only web pages become start-page links: this HTML renders as a
        // local file page, so other schemes (javascript:, intent:, ...) are
        // never emitted as hrefs. esc() still guards every interpolated value.
        val webBookmarks = bookmarks.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
        val webHistory = history.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
        val tiles =
            webBookmarks.joinToString("") {
                val host = it.url.substringAfter("://").substringBefore('/').removePrefix("www.")
                val letter = it.title.firstOrNull()?.uppercaseChar() ?: '•'
                """<a class="tile" href="${esc(it.url)}"><span class="glyph" style="--h:${hostHue(host)}">${esc(letter.toString())}</span><span class="label">${esc(it.title)}</span></a>"""
            }
        val recent =
            webHistory.take(6).joinToString("") {
                val host = hostOf(it.url)
                """<a class="row" href="${esc(it.url)}"><span class="rglyph" style="--h:${hostHue(host)}">${esc(host.firstOrNull()?.uppercaseChar()?.toString() ?: "?")}</span><span class="rtext"><span class="rtitle">${esc(it.title)}</span><span class="rhost">${esc(host)}</span></span></a>"""
            }
        val html =
            """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Argon</title><style>
:root{--bg:#08090C;--surface:#101318;--high:#181C23;--text:#F2F4F8;--text2:#9AA1AD;--text3:#79828F;--accent:#7AA7FF;--accentsoft:#7AA7FF1F;--hair:#FFFFFF12;--hair2:#FFFFFF1F}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Roboto,sans-serif;min-height:100vh;padding:40px 20px 96px}
.wrap{max-width:560px;margin:0 auto}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:26px}
.mark{width:36px;height:36px}
.word{font-size:21px;font-weight:600;letter-spacing:.01em;color:var(--text)}
form{margin-bottom:36px;position:relative}
.ficon{position:absolute;left:20px;top:50%;transform:translateY(-50%);width:18px;height:18px;color:var(--text3);pointer-events:none}
input{width:100%;height:52px;border-radius:26px;border:1px solid var(--hair);background:var(--surface);color:var(--text);padding:0 24px 0 50px;font-size:17px;outline:none;transition:border-color .15s}
input:focus{border-color:var(--accent)}
input::placeholder{color:var(--text3)}
.stitle{color:var(--text3);font-size:13px;margin-bottom:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(92px,1fr));gap:12px}
.tile{display:flex;flex-direction:column;align-items:center;gap:10px;padding:16px 8px;background:var(--surface);border:1px solid var(--hair);border-radius:16px;text-decoration:none;color:var(--text2);font-size:13px;transition:border-color .15s,background .15s}
.tile:active{border-color:var(--hair2);background:var(--high)}
.glyph{width:44px;height:44px;border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:500;background:hsl(var(--h),38%,22%);color:hsl(var(--h),70%,78%)}
.label{max-width:100%;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;white-space:normal;text-align:center;line-height:1.3;min-height:2.6em}
.empty{color:var(--text3);font-size:14px;padding:8px 2px}
.recent{margin-top:36px}
.rows{display:flex;flex-direction:column}
.row{display:flex;align-items:center;gap:14px;padding:11px 4px;text-decoration:none;border-bottom:1px solid var(--hair)}
.row:last-child{border-bottom:none}
.row:active{background:var(--high)}
.rglyph{width:30px;height:30px;border-radius:8px;flex:none;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:500;background:hsl(var(--h),38%,22%);color:hsl(var(--h),70%,78%)}
.rtext{display:flex;flex-direction:column;min-width:0}
.rtitle{color:var(--text);font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rhost{color:var(--text3);font-size:12px}
@media (prefers-reduced-motion:no-preference){
.brand,form{animation:rise .3s cubic-bezier(.2,.7,.3,1) backwards}
.tile{animation:rise .3s cubic-bezier(.2,.7,.3,1) backwards}
.tile:nth-child(2){animation-delay:.03s}.tile:nth-child(3){animation-delay:.06s}
.tile:nth-child(4){animation-delay:.09s}.tile:nth-child(5){animation-delay:.12s}.tile:nth-child(6){animation-delay:.15s}
.tile:nth-child(7){animation-delay:.18s}.tile:nth-child(8){animation-delay:.21s}
.rows .row{animation:rise .3s cubic-bezier(.2,.7,.3,1) backwards}
.rows .row:nth-child(2){animation-delay:.05s}.rows .row:nth-child(3){animation-delay:.1s}
.rows .row:nth-child(4){animation-delay:.15s}.rows .row:nth-child(5){animation-delay:.2s}.rows .row:nth-child(6){animation-delay:.25s}
}
@keyframes rise{from{opacity:0;transform:translateY(10px)}}
</style></head><body>
<div class="wrap">
<div class="brand"><svg class="mark" viewBox="0 0 108 108" fill="none"><circle cx="54" cy="54" r="24" stroke="#7AA7FF" stroke-width="4.5" stroke-linecap="round"/><circle cx="79.5" cy="44" r="3.4" fill="#7AA7FF"/></svg><span class="word">Argon</span></div>
<form action="${esc(engine.searchUrlPrefix)}" method="get"><svg class="ficon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.8-3.8"/></svg><input type="search" name="${esc(engine.queryParam)}" placeholder="Search the web" autocomplete="off"></form>
<div class="stitle">Start</div><div class="grid">$tiles</div>${if (webBookmarks.isEmpty()) """<div class="empty">Pages you star from the menu land here.</div>""" else ""}${if (recent.isNotEmpty()) """<div class="recent"><div class="stitle">Recent</div><div class="rows">$recent</div></div>""" else ""}
</div>
</body></html>"""
        val file = File(context.filesDir, "startpage.html")
        file.writeText(html)
        return file.toURI().toString()
    }

    /** Deterministic per-host hue (0-359), so a shortcut's tile keeps its own
     * muted identity color between launches. Local and stateless; no assets,
     * no network. Saturation/lightness stay inside the quiet palette. */
    private fun hostHue(host: String): Int {
        var h = 0
        for (c in host) h = (h * 31 + c.code) and 0x7FFFFFFF
        return h % 360
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
