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

## net_h2_h1_fallback.patch + net_connector_h1_client.patch + net_resource_thread_h1_state.patch

Target: `components/net/{connector,http_loader,resource_thread}.rs`. Targeted
HTTP/1.1 fallback for HTTP/2 stream resets.

Problem (user-reported on Argon 1.0.7 for youtube.com; upstream servo/servo#39785
documents the same user-facing error for gemini.google.com on nightly 20261101,
Android + desktop, with inner error `hyper::Error(Http2, Reset(StreamId(1),
PROTOCOL_ERROR))`): some front ends reset Servo's first h2 stream, surfacing as
"Could not load the requested page: client error (SendRequest)".

What the patches do:
1. `connector.rs`: `create_client(tls_config, http2)` factory; `create_http_client`
   keeps h2 enabled, new `create_http1_client` builds an HTTP/1.1-only client.
2. `resource_thread.rs`: `HttpState` gains `client_http1` (public + private states).
3. `http_loader.rs`: after the primary request fails with exactly the h2
   PROTOCOL_ERROR reset (Debug-match, no new dependency), the request is retried
   once over the h1-only client - ONLY for bodiless GET/HEAD, which are safe to
   replay. All other methods/requests keep the original error; h2 stays enabled
   everywhere else.

Status: PRODUCTION CANDIDATE pending device verification - t9 (YouTube
navigation with inner-error log capture) and broad site-loading/performance
comparison against the unpatched engine before any adoption.

## diagnostic/net_http1_only.patch (NOT applied by the workflow)

Diagnostic variant: disables HTTP/2 entirely (ALPN offers HTTP/1.1 only).
Built once to test whether the h1 path makes Google properties load at all.
Trade-off: all HTTPS sites lose h2 multiplexing. Never ship as latest; compare
site-loading and browsing performance before drawing conclusions.
