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
Reinstall: git clone --depth 1 <url> for each into the agent workspace.

## Revamp scope status (branch exp-ui-revamp, tip green on CI lint)
- Navigation: layered back handling (suggestions -> focus -> page history -> screens), contextual back inside the capsule.
- Search: local-only omnibox suggestions from device history + bookmarks (no network), IME Go, hardware Enter/Escape, select-all on focus, clear button.
- Menus: section grouping (This page / App), Share page (Android share sheet), Bookmarks screen row, Reader view, View DOM.
- Bookmarks: dedicated screen (open/remove), start page rewrites on change so tiles and screen never disagree; import/export retained.
- History: local filter field with explicit no-match empty state; start page Recent section (6 rows).
- Onboarding: off-white primary buttons, welcome orbit mark.
- Reader: bundled Mozilla Readability 0.6.0 (Apache-2.0) extraction, serif reading family, reading-progress hairline, loading pill, error dialog, stale-result guard.
- Settings: section grouping; search engine, block list, bookmarks import/export.
- Accessibility: 48dp touch targets in the capsule, content descriptions on icon buttons, Text3 #79828F (~4.6:1 on background).
- Empty states: history, bookmarks, filter no-match, start page.
- Start page: pixel-verified in headless Chrome at 360x760 and 412x900 (three columns, wrapped labels, Recent section).

## Embedding limitations (accepted backlog; need engine work, not UI work)
- Tabs: the Servo embedding exposes a single surface; no multi-view API. Tabs deferred.
- Network-error page: no load-failure callback exists (navigate/back/forward/reload/stop + evaluateScript only), so an honest error state cannot be built. Not faked.

## Verification honesty
- Start page: pixel-verified (headless Chrome against the live template).
- Compose screens (menu, pages, reader, onboarding, chrome): compile + CI lint verified only; no device rendering performed. Treat as visually unverified until a device run exists.
