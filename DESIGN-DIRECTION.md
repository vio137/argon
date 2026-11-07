# Argon UI revamp - design direction (unslop step 0)

- Product: a Servo-engine Android browser for a technical user who wants speed and quiet.
- Character: quiet, exact, engineered.
- World: argon the element - dark instrument lab, machined graphite, one cool discharge glow.
  Neutrals tint cool (blue-grey graphite), never violet.
- Moments: (1) the orbit mark on the start page, (2) the thin loading track on the command
  capsule. Everything else stays neutral so page content carries color.
- Signature: the orbit dot (circle + offset dot) from the Argon mark, reused for progress and
  empty states. One detail, used sparingly.
- Accent: instrument azure #7AA7FF (argon discharge blue), not the old AI-violet #8B7CF6.
  Primary buttons on dark use off-white with dark text; accent marks selection, focus, links
  and the loading track only.
- Type: one family (system), 400/500 weights, 600 rare, no uppercase tracked labels,
  sentence case, tabular figures for counts.
- Motion: one spring spec family, short fades; no loops, no bounce, no animate-everything.
- Depth: surface steps + hairlines only; no heavy black shadows, no hue gradients, no washes.

## Skill pins (installed to agent workspace; read-only /skills not writable, recoverable recipe)
- unslop @ a6f121e https://github.com/heydamir/unslop CC-BY-4.0 (credit: Damir Sokolovsky)
- apple-design-skill @ ff4ebdd https://github.com/dickwu/apple-design-skill (HIG reference set)
- mobile-app-ui-design @ 0ad61f0 https://github.com/ceorkm/mobile-app-ui-design
- ponytail @ 96608e0 https://github.com/dietrichgebert/ponytail MIT
- compose-animations (chrisbanes/skills) https://github.com/chrisbanes/skills Apache-2.0 (animation API selection)
- android-compose-design @ NI3singh https://github.com/NI3singh/android-compose-design-skill MIT (anti-generic Android UI; motion.md entrance choreography)
Note: rock3r/android-ux-skills (animate-compose) 404s as of 2026-11-07 - not installable.
Reinstall: git clone --depth 1 <url> for each into the agent workspace.

## Revamp scope status (branch exp-ui-revamp, tip green on CI lint)
- Navigation: layered back handling (suggestions -> focus -> page history -> screens), contextual back inside the capsule.
- Search: local-only omnibox suggestions from device history + bookmarks (no network), IME Go, hardware Enter/Escape, select-all on focus, clear button.
- Menus: section grouping (This page / App), Share page (Android share sheet), Bookmarks screen row, Reader view, View DOM.
- Bookmarks: dedicated screen (open/remove), start page rewrites on change so tiles and screen never disagree; import/export retained.
- History: local filter field with explicit no-match empty state; start page Recent section (6 rows).
- Onboarding: off-white primary buttons, welcome orbit mark.
- Settings: section grouping; search engine, block list, bookmarks import/export.
- Accessibility: 48dp touch targets in the capsule, content descriptions on icon buttons, Text3 #79828F (~4.6:1 on background).
- Empty states: history, bookmarks, filter no-match, start page.
- Start page: pixel-verified in headless Chrome at 360x760 and 412x900 (three columns, wrapped labels, Recent section).

- Find in page: app-bundled JS helper via the evaluateScript bridge (mark-wrap, next/prev scroll-into-view, 500-match cap, generation-bound results). JS logic verified in headless Chrome against a fixture page (5-match case, script-tag exclusion, case-insensitivity, clear, empty query). Bridge symbol confirmed exported in the shipped v15 .so (nm -D). Bridge runtime behavior on device remains unverified.
- Copy link: menu row, clipboard copy of the current page URL.
- Search engines: Google, DuckDuckGo, Brave, Bing (settings).

## Embedding gaps under investigation (open engineering outcomes, not exclusions)
- Tabs: the current Servo embedding exposes a single surface with no multi-view API. Investigation path: servoshell/host-side view management or a surface-swap model; not dropped, not faked with screenshots.
- Network-error page: no load-failure callback exists today (navigate/back/forward/reload/stop + evaluateScript only). Investigation path: host-side load-state plumbing in the servo package; an honest error state stays on the list.

## Verification honesty
- Start page: pixel-verified (headless Chrome against the live template).
- Compose screens (menu, pages, onboarding, chrome): compile + CI lint verified only; no device rendering performed. Treat as visually unverified until a device run exists.
- The 2026-11-07 passes over all screens were a STATIC FLOW REVIEW (code-level walk of states, enabled gates, escape paths and store handling). They are not visual, spatial or runtime verification.

## User-directed exclusions (2026-11-07, user's own words)
- Reader view and View DOM: user tried them on the delivered candidate, said they do not work and "I don't want those features". Both removed from the next build: menu rows, overlays, ReaderMode.kt, ViewDom.kt, readability.js asset, icons, About credit, serif article type styles. The engine's evaluateScript bridge stays in the servo package (it is engine API, unused features cost nothing).

## Motion rules (from compose-animations + android-compose-design, applied)
- Springs for spatial motion, one 160ms effects tween; both in Motion.kt (ArgonMotion).
- One orchestrated moment per surface: secondary pages rise-and-fade once on
  entry (PageEntrance), the start page staggers tiles/rows via CSS, onboarding
  steps slide. Nothing loops; no bounce.
- Reduced motion honored: PageEntrance reads the system transition-animation
  scale and jumps to end state; the start page gates its CSS on
  prefers-reduced-motion: no-preference.
- Frame-rate values read inside graphicsLayer blocks, never in composition.

## System-back precedence (static review, 2026-11-07; compile-verified, not runtime-tested)

Compose rule: the last-composed ENABLED BackHandler in the window wins. Gates
below make the activity-level handlers mutually exclusive by construction.
Dialog windows (M3 ModalBottomSheet, every AlertDialog: engine alert, restart,
rename, clear-history, engine picker, About) are separate windows - system
back is delivered to the dialog and dismisses it; activity BackHandlers never
see it. IME up: the system consumes the first back to collapse the keyboard
before any Compose handler runs.

Composition order and gates:
1. Omnibox focused + blank query -> clearFocus (editing exits first).
2. Omnibox focused + query -> clearFocus (closes suggestions, restores host).
3. History/Bookmarks/Settings screen -> back to Browser (Bookmarks refreshes
   the start page). Only composed on that screen.
4. Onboarding overlay: BackHandler(enabled = page > 0) steps back a page; on
   page 0 no handler is enabled, so back exits first run (Skip is on screen).
5. Browser back chain, gated on: Browser screen, no onboarding, no menu, no
   find bar, omnibox NOT focused. Order inside: find is closed by its own
   handler (6); page history via navigator.canGoBack; at the root, first
   press arms a 2.5s window with a toast, second press finishAndRemoveTask().
   The arm resets on every onUrlChanged so it cannot leak onto a later root.
6. Find bar visible -> closeFind (composed after 5; 5 is gated !findVisible).

Known limitation: precedence is established statically from composition order
and gates; no device run has exercised the matrix.

## Next-build inclusion list (exact; nothing else rides along)

Included since the delivered candidate (vc18): theme/menu/onboarding revamp,
start page v3 + integrity hardening, local suggestions, share target
(hardened), share page with title, copy link, open in another app, bookmarks
screen + import/export + rename, history filter/remove/day-grouping/time-range
clear, find in page (EXPERIMENTAL: bridge runtime-unverified), motion layer +
reduced-motion compliance, 48dp touch targets, state-aware star toggle,
onboarding skip + back steps, system-back chain with press-again exit,
About version from BuildConfig, no shipped sample bookmarks, no Reader/ViewDOM
(user-directed, effective until the user changes their intent).

Excluded from the next build: Reader view and View DOM (user's own words,
2026-11-07; exclusion holds until the user changes their intent, it is not
irrevocable). Toolbar-on-top is excluded from the delivered build entirely -
no UI, no activation path - until the flipped layout has spatial evidence
gathered under allowed methods; the source is preserved on branch
exp-toolbar-top for later. Interface copy never carries test badges or
verification disclaimers; caveats live in handoffs and this file, not in the
shipped UI. Tabs and the network-error page are open gaps under
investigation, not in this build.
