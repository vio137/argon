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

## Load-error host signal build (exp-load-error)
- servo_ref: b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd (unchanged from v15)
- patch set applied by the engine workflow in glob order: 006_media_backend,
  020_load_error_host_signal, font_list, js_eval_bridge, net_connector_h1_client,
  net_h2_h1_fallback, net_resource_thread_h1_state
- 020 adds a top-level load-error signal: servoparser NetworkError choke point
  -> ScriptToConstellationMessage::LoadError -> ConstellationToEmbedderMsg::LoadError
  -> WebViewDelegate::notify_load_error -> HostTrait::on_load_error -> JNI
  onLoadError(webviewId, pipelineId, url, kind, reason, generation). Top-level
  only at the source (window.is_top_level); reason is a fixed ASCII category
  token, never logged raw; TLS failures are a distinct kind with no bypass
  affordance. A second signal, TopLevelLoadStarted, announces every top-level
  load start with its request identity (webview, pipeline, generation).
- App side: MainActivity LoadErrorCard overlay gated by LoadErrorGate: the
  error card shows only when the event's (webviewId, pipelineId, generation)
  triple exactly matches the current request identity. URL is display payload
  only (redirects change it mid-load). App-initiated navigation (omnibox
  submit, back/forward/reload/stop) invalidates the identity immediately,
  before the engine's start callback arrives.

## Tabs multiview build (exp-tabs)
- servo_ref: b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd (unchanged from v15)
- patch set: everything in exp-load-error plus 030_tabs_multiview (applies
  after 020 in glob order; chain apply-checked 020 then 030 on pristine base).
- 030 adds tab management to the Android host: App::new_tab/activate_tab/
  close_tab/active_tab_id over the existing ServoShellWindow webview
  collection (create_and_activate_toplevel_webview, activate_webview,
  close_webview - the desktop servoshell tabs model), exposed over JNI as
  newTab(url): String, activateTab(id): Boolean, closeTab(id): Boolean,
  activeTabId(): String. Tab ids cross JNI as opaque WebViewId Display
  strings, matched by exact string equality engine-side. Navigation commands
  (loadUri/back/forward/reload/stop) and title/url/history/load-status
  callbacks already act on the ACTIVE webview (update_user_interface_state),
  so switching tabs re-reports the new active tab's state without callback
  multiplexing. Closing the active tab activates the next-newest; the app
  keeps at least one tab open by UI rule.

### 020 revision history
- v1 (2ad46c0): initial signal; raw bounded reason string (RETRACTED:
  String::truncate could split a UTF-8 boundary, and raw provider detail was
  over-shared). Engine build run 42352632037 compile-checks v1 as an exact
  snapshot; its artifact is not a candidate.
- v2 (current): payload is a fixed ASCII category token (tls_validation,
  crash, http_error, connection_failed, redirect, blocked, resource,
  network) - Unicode-safe by construction, no provider detail. Staleness
  gate moved to the authoritative layer: the constellation forwards only
  when the failed pipeline is the webview's active top-level document or an
  in-flight navigation target (pipeline identity, not URL equality).
  App-side URL matching remains secondary defense.
- v3 (current): end-to-end request identity. The constellation assigns a
  per-webview top-level load generation (u64, saturating) at every top-level
  load start - fresh navigations (load_url), history-traversal reloads
  (activate_history_entry_in_browsing_context, top-level browsing contexts
  only), and crash-recovery loads (handle_panic_in_webview) - records it per
  pipeline, and emits TopLevelLoadStarted(webview, pipeline, url, generation).
  LoadError events carry the failing load's generation. The app
  (LoadErrorGate) shows the card only on exact triple match and invalidates
  immediately on any app-initiated navigation; both engine signals travel the
  same FIFO constellation->embedder channel and the same main-dispatcher
  dispatch, so a load's start always precedes its failure. Generation 0 marks
  untracked loads (iframe traversal pipelines, pre-tracking pipelines) and
  never matches. Pipeline-close removes the generation entry. Kotlin
  LoadErrorGate state machine covered by 16 standalone assertions
  (LoadErrorGateTest: stale-before-new-start, repeated same URL, redirect,
  superseded pending load, out-of-order delivery, untracked gen, lifecycle
  reset) - compiled and run with the standalone kotlinc recipe, all pass;
  the full Gradle unit-test task remains unrun.
- v3.1 (current): generation overflow is fail-closed (checked_add; on
  exhaustion the start is announced with generation 0, which never matches
  app-side, instead of reusing a saturated max generation), and auxiliary
  webview creation (handle_script_new_auxiliary) announces its initial
  top-level load's identity so auxiliary failures are matchable like any
  other. Gate: a gen-0 start clears the identity. 19 standalone assertions
  pass.
- v3.2 (current): compile fix - the v1-v3.1 delegate overrides sat in the
  wrong impl block (impl PlatformWindow without the trait declaring them;
  v1 engine run 42352632037 failed with E0407). v3.2 declares
  notify_load_error/notify_top_level_load_started on the PlatformWindow
  trait (ports/servoshell/window.rs, default no-ops, mirroring
  notify_crashed) and forwards WebViewDelegate -> PlatformWindow in
  RunningAppState (running_app_state.rs). Patch now spans 14 files;
  apply-checked against pristine base.
