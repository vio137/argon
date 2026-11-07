# Argon visual redesign - direction record (v2, "Argon discharge")

Built from scratch per user request (2026-11-07 17:37). Supersedes the v1
"quiet instrument" azure system. Unslop by Damir Sokolovsky, CC BY 4.0.

## Language
- Deep graphite base (#0B0C10), raised layer #13151B, surfaces #181A22 / #21242E.
- Single argon-discharge violet accent #9B8CFF: selection, focus, secure
  glyph, links, loading track. Nothing else.
- Depth from surface steps, translucency and hairlines (0x17/0x29 white) -
  never heavy shadows or hue gradients.
- Type: quiet hierarchy, restrained weights; section labels at 12.5sp/1.1ls.

## Structure
- Unified bottom deck: one glass surface holding back/forward, the omnibox
  pill, stop-or-reload and menu. Loading is a thin accent track along the
  deck's bottom edge. Focused state collapses nav buttons; trailing slot
  crossfades to an accent Go button.
- Start page: wordmark, search field, per-site tinted monogram tiles,
  Recent card.
- Radii: controls 16, cards 20, deck 26.

## Honesty constraints (from main review 2026-11-07)
- No "Private by design" or any privacy slogan. No experimental-engine badge.
- The lock glyph derives only from the observed page URL scheme (https) - a
  transport indicator, never a verified-security claim.
- No fixture/stock imagery ships as app artwork.
- Compose cannot backdrop-blur the webview; the deck glass is a high-alpha
  raised surface instead. Noted, not faked.
- Touch targets >=44dp; contrast readable at narrow widths; ime/nav insets
  respected.

## Verification lane
HTML mock screenshots (headless chrome) are design-direction evidence only.
Shipped Kotlin is compile + lint verified; an actual Compose rendering path
without device/emulator is not available under current constraints (no
Paparazzi/Roborazzi test lane, no Compose Desktop port). Pixel claims wait
for user device feedback.
