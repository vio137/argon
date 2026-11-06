# Servo font fallback patch (font_list.patch)

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

Apply: `git apply .github/servo-font-patch/font_list.patch` inside a servo checkout.
