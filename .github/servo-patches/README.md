# Servo engine patches

Target: servo/servo @ b6c9df76, file `components/fonts/platform/freetype/android/font_list.rs`.

Problem (device-evidenced on API 35): Android's fonts.xml ships most script fonts
(Bengali, Devanagari, CJK, ...) in UNNAMED, language-tagged `<family>` entries that
carry only a `postScriptName` attribute on the `<font>` child. Stock Servo's
`parse_family` early-returns on any family without a `name` attribute, discarding
~149 families; its `find_family` matches case-insensitively only, so engine
fallback requests like "Noto Sans Bengali" never resolve and text renders as tofu.

What the patch does:
1. `parse_family`: when a family has no `name`, synthesizes one from the first
   font child's `postScriptName` (style suffix stripped, e.g.
   "NotoSansBengali-Regular" -> "NotoSansBengali") and registers the family.
2. `find_family`: after the exact case-insensitive match, falls back to a
   normalized comparison (ASCII alphanumeric, lowercase) so "Noto Sans Bengali"
   matches "NotoSansBengali".

Known follow-ups (deliberately not in this patch):
- TTC `index` attribute is not honored; NotoSansCJK-Regular.ttc will resolve to
  its first face (Japanese). Chinese/Korean glyph coverage needs face-index
  support in the FreeType loader, a separate change.
- CJK fallback names like "Noto Sans CJK JP" do not normalized-match
  "NotoSansCJK"; call sites should include the synthetic base name too.
- Not upstreamed; upstreaming needs a proper design discussion.

Apply: `git apply .github/servo-patches/font_list.patch` inside a servo checkout.

## net_http1_only.patch

Target: `components/net/connector.rs`. Disables HTTP/2 (ALPN offers HTTP/1.1 only).

Problem (user-reported on Argon 1.0.7; upstream servo/servo#39785 reproduced on
nightly 20261101, Android and desktop): Google front ends (youtube.com,
gemini.google.com, ...) reset the first h2 stream with PROTOCOL_ERROR, logged as
`hyper::Error(Http2, Reset(StreamId(1), PROTOCOL_ERROR))` and surfaced to the
user as "Could not load the requested page: client error (SendRequest)".

Trade-off: all HTTPS sites lose h2 multiplexing until the upstream h2
incompatibility is understood and fixed (follow-up: h1 retry on that specific
error instead of a blanket disable).
