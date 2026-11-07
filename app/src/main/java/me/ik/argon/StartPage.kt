package me.ik.argon

import android.content.Context
import java.io.File

object StartPage {
    fun write(context: Context, bookmarks: List<Bookmark>, engine: SearchEngine): String {
        val tiles =
            bookmarks.joinToString("") {
                val letter = it.title.firstOrNull()?.uppercaseChar() ?: '•'
                """<a class="tile" href="${esc(it.url)}"><span class="glyph">${esc(letter.toString())}</span><span class="label">${esc(it.title)}</span></a>"""
            }
        val html =
            """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><title>Argon</title><style>
:root{--bg:#08090C;--surface:#101318;--high:#181C23;--text:#F2F4F8;--text2:#9AA1AD;--text3:#626A76;--accent:#7AA7FF;--accentsoft:#7AA7FF1F;--hair:#FFFFFF12;--hair2:#FFFFFF1F}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Roboto,sans-serif;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:40px 24px 110px}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:36px}
.mark{width:36px;height:36px}
.word{font-size:21px;font-weight:600;letter-spacing:.01em;color:var(--text)}
form{width:100%;max-width:560px;margin-bottom:52px;position:relative}
.ficon{position:absolute;left:20px;top:50%;transform:translateY(-50%);width:18px;height:18px;color:var(--text3);pointer-events:none}
input{width:100%;height:52px;border-radius:26px;border:1px solid var(--hair);background:var(--surface);color:var(--text);padding:0 24px 0 50px;font-size:17px;outline:none;transition:border-color .15s}
input:focus{border-color:var(--accent)}
input::placeholder{color:var(--text3)}
.section{width:100%;max-width:560px}
.stitle{color:var(--text3);font-size:13px;margin-bottom:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(92px,1fr));gap:12px}
.tile{display:flex;flex-direction:column;align-items:center;gap:12px;padding:20px 8px;background:var(--surface);border:1px solid var(--hair);border-radius:16px;text-decoration:none;color:var(--text2);font-size:13px;transition:border-color .15s,background .15s}
.tile:active{border-color:var(--hair2);background:var(--high)}
.glyph{width:44px;height:44px;border-radius:12px;background:var(--accentsoft);color:var(--accent);display:flex;align-items:center;justify-content:center;font-size:18px;font-weight:500}
.label{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.empty{color:var(--text3);font-size:14px;padding:8px 2px}
.footer{position:fixed;bottom:28px;color:var(--text3);font-size:12px}
</style></head><body>
<div class="brand"><svg class="mark" viewBox="0 0 108 108" fill="none"><circle cx="54" cy="54" r="24" stroke="#7AA7FF" stroke-width="4.5" stroke-linecap="round"/><circle cx="79.5" cy="44" r="3.4" fill="#7AA7FF"/></svg><span class="word">Argon</span></div>
<form action="${esc(engine.searchUrlPrefix)}" method="get"><svg class="ficon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.8-3.8"/></svg><input type="search" name="${esc(engine.queryParam)}" placeholder="Search the web" autocomplete="off"></form>
<div class="section"><div class="stitle">Start</div><div class="grid">$tiles</div>${if (bookmarks.isEmpty()) """<div class="empty">Pages you star from the menu land here.</div>""" else ""}</div>
<div class="footer">The quiet browser</div>
</body></html>"""
        val file = File(context.filesDir, "startpage.html")
        file.writeText(html)
        return file.toURI().toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
