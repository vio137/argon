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
:root{--bg:#050507;--surface:#101014;--high:#1C1C23;--text:#F5F5F7;--text2:#9B9BA4;--text3:#6E6E78;--accent:#8B7CF6}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Roboto,sans-serif;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px 24px 96px}
.mark{width:56px;height:56px;margin-bottom:20px}
h1{font-size:28px;font-weight:600;letter-spacing:.14em;margin-bottom:4px}
.sub{color:var(--text3);font-size:13px;margin-bottom:40px}
form{width:100%;max-width:520px;margin-bottom:48px}
input{width:100%;height:52px;border-radius:26px;border:1px solid #FFFFFF24;background:var(--surface);color:var(--text);padding:0 22px;font-size:17px;outline:none}
input:focus{border-color:var(--accent)}
input::placeholder{color:var(--text3)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:12px;width:100%;max-width:520px}
.tile{display:flex;flex-direction:column;align-items:center;gap:10px;padding:18px 8px;background:var(--surface);border:1px solid #FFFFFF14;border-radius:20px;text-decoration:none;color:var(--text2);font-size:13px;transition:border-color .15s}
.tile:active{border-color:var(--accent)}
.glyph{width:40px;height:40px;border-radius:12px;background:#8B7CF61F;color:var(--accent);display:flex;align-items:center;justify-content:center;font-size:17px;font-weight:600}
.footer{position:fixed;bottom:24px;color:var(--text3);font-size:13px}
</style></head><body>
<svg class="mark" viewBox="0 0 108 108" fill="none"><circle cx="54" cy="54" r="24" stroke="#8B7CF6" stroke-width="4.5" stroke-linecap="round"/><circle cx="79.5" cy="44" r="3.4" fill="#8B7CF6"/></svg>
<h1>ARGON</h1>
<div class="sub">The quiet browser</div>
<form action="${esc(engine.searchUrlPrefix)}" method="get"><input type="search" name="${esc(engine.queryParam)}" placeholder="Search the web" autocomplete="off"></form>
<div class="grid">$tiles</div>
</body></html>"""
        val file = File(context.filesDir, "startpage.html")
        file.writeText(html)
        return file.toURI().toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
