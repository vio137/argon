package me.ik.argon

import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Find in page. A small app-bundled JS helper is installed on the page via the
 * engine's evaluateScript bridge; it wraps matches in <mark>, tracks the
 * current match, and scrolls it into view. The completion value of every
 * script is the result OBJECT (the bridge serializes it once - never return
 * JSON.stringify output, that double-encodes). Only bundled source is
 * evaluated, only on explicit user action, results never leave the app.
 */
object FindInPage {
    data class Result(val count: Int, val index: Int)

    /** Idempotent helper install; completion value "ok". */
    private const val HELPER =
        """
(function(){
if (window.__argonFind) return "ok";
var MARK = "argon-find-hit";
var IDLE = "background:rgba(122,167,255,.45);color:inherit;border-radius:2px;padding:0;";
var ACTIVE = "background:#7AA7FF;color:#08090C;border-radius:2px;padding:0;";
var marks = [];
var cur = 0;
function clear() {
  for (var i = 0; i < marks.length; i++) {
    var m = marks[i], p = m.parentNode;
    if (p) { p.replaceChild(document.createTextNode(m.textContent), m); p.normalize(); }
  }
  marks = []; cur = 0;
}
function highlight(q) {
  clear();
  if (!q || !document.body) return {c:0, i:0};
  var ql = q.toLowerCase();
  var walker = document.createTreeWalker(document.body, 4, { acceptNode: function(n) {
    if (!n.nodeValue || n.nodeValue.toLowerCase().indexOf(ql) === -1) return 2;
    var t = n.parentNode && n.parentNode.tagName;
    if (t === "SCRIPT" || t === "STYLE" || t === "NOSCRIPT" || t === "TEXTAREA" || t === "INPUT") return 2;
    return 1;
  }});
  var nodes = [], n;
  while ((n = walker.nextNode())) nodes.push(n);
  for (var k = 0; k < nodes.length && marks.length < 500; k++) {
    var node = nodes[k];
    if (!node.parentNode) continue;
    var text = node.nodeValue, lower = text.toLowerCase(), pos = 0, idx, frag = null;
    while ((idx = lower.indexOf(ql, pos)) !== -1 && marks.length < 500) {
      if (!frag) frag = document.createDocumentFragment();
      if (idx > pos) frag.appendChild(document.createTextNode(text.slice(pos, idx)));
      var m = document.createElement("mark");
      m.className = MARK;
      m.textContent = text.slice(idx, idx + q.length);
      m.setAttribute("style", IDLE);
      frag.appendChild(m);
      marks.push(m);
      pos = idx + q.length;
    }
    if (frag) {
      if (pos < text.length) frag.appendChild(document.createTextNode(text.slice(pos)));
      node.parentNode.replaceChild(frag, node);
    }
  }
  if (marks.length) { cur = 1; show(1); }
  return {c: marks.length, i: marks.length ? 1 : 0};
}
function show(i) {
  for (var j = 0; j < marks.length; j++) {
    var m = marks[j];
    if (j === i - 1) {
      m.setAttribute("style", ACTIVE);
      if (m.scrollIntoView) m.scrollIntoView({block: "center"});
    } else {
      m.setAttribute("style", IDLE);
    }
  }
}
window.__argonFind = {
  highlight: highlight,
  next: function() { if (!marks.length) return {c:0,i:0}; cur = cur % marks.length + 1; show(cur); return {c: marks.length, i: cur}; },
  prev: function() { if (!marks.length) return {c:0,i:0}; cur = (cur - 2 + marks.length) % marks.length + 1; show(cur); return {c: marks.length, i: cur}; },
  clear: function() { clear(); return {c:0,i:0}; }
};
return "ok";
})();
"""

    fun highlightScript(query: String): String =
        HELPER + "\n;window.__argonFind.highlight(" + JSONObject.quote(query) + ")"

    const val NEXT_SCRIPT = "window.__argonFind ? window.__argonFind.next() : null"
    const val PREV_SCRIPT = "window.__argonFind ? window.__argonFind.prev() : null"
    const val CLEAR_SCRIPT = "window.__argonFind ? window.__argonFind.clear() : null"

    /** Parse the bridge payload; null when the page had no helper or result. */
    fun parse(json: String?): Result? {
        if (json == null || json == "null" || json.length > 4_096) return null
        return try {
            val obj = JSONObject(json)
            Result(count = obj.optInt("c", 0), index = obj.optInt("i", 0))
        } catch (e: Exception) {
            null
        }
    }
}

// ---- Bar UI ----

@Composable
fun FindBar(
    query: String,
    count: Int,
    index: Int,
    onQuery: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(ArgonColors.SurfaceHigh)
                .padding(start = 16.dp, end = 4.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = ArgonType.BodySmall.copy(color = ArgonColors.Text),
            cursorBrush = SolidColor(ArgonColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        ) { inner ->
            androidx.compose.foundation.layout.Box {
                if (query.isEmpty()) Text("Find in page", color = ArgonColors.Text3, style = ArgonType.BodySmall)
                inner()
            }
        }
        if (query.isNotEmpty()) {
            Text(
                if (count == 0) "0/0" else "$index/$count",
                color = ArgonColors.Text3,
                style = ArgonType.Caption,
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onPrev, enabled = count > 0) {
            Icon(painterResource(R.drawable.ic_chevron_up), contentDescription = "Previous match", tint = if (count > 0) ArgonColors.Text else ArgonColors.Text3, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onNext, enabled = count > 0) {
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = "Next match", tint = if (count > 0) ArgonColors.Text else ArgonColors.Text3, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Close find", tint = ArgonColors.Text3, modifier = Modifier.size(18.dp))
        }
    }
}
