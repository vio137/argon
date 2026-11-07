# Servo patch-set composition (exp-tabs, tip 208d0b7)

Base: servo b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd (pin). CI glob-applies in name order.

## Active (shipped in candidates)
- 006_media_backend.patch - Android media backend
- 020_load_error_host_signal.patch - load-error host signalling
- 030_tabs_multiview.patch - multi-tab/webview support
- font_list.patch - lang-tagged unnamed family registration + normalized matching
- js_eval_bridge.patch - JS evaluation bridge
- net_connector_h1_client.patch / net_h2_h1_fallback.patch / net_resource_thread_h1_state.patch - HTTP/1.1 robustness
- 050_android_ttc_face_index.patch - fonts.xml index attr -> face_index (TTC faces); v2 validated u16

## DORMANT - not user-facing, pref OFF, never in EXPERIMENTAL_PREFS
- 040_mse_traits.patch + 041_mse_script.patch - MSE DOM surface draft.
  Verification level: compile + host assertions only. NOT a shipped capability.
  Must be EXCLUDED from any font/perf release-candidate assembly until main clears it.

## Separability
Font/perf/fling work (050, planned 052) has no dependency on 040/041.
A font+fling candidate can be assembled from pin + active set only.
