# Berilo — Design Guidelines

> The text is the hero. Chrome recedes. The app should feel like a well-set
> book, not an app.

## Principles

1. **Deference** — while reading, zero persistent UI. Tap center toggles a
   minimal overlay (progress, chapter, settings). Everything else is gesture.
2. **E-ink first** — the primary device is a Boox tablet. True black on true
   white, no low-contrast grays for body text, no animation in e-ink mode,
   full-refresh page turns. OLED phones get the same layout with a dark theme.
3. **Typographic restraint** — one serif for body (Literata — designed for
   e-readers, has full Slovenian diacritics), one sans for UI (Inter). Modular
   scale 1.25. Generous margins (min 24 dp), line length 55–70 chars, line
   height 1.5.
4. **One accent color** — deep amber `#B45309` (bookish, visible on e-ink as
   dark gray). Highlights: 4 muted fills that stay legible in grayscale.
5. **Instant, quiet feedback** — lookups open a bottom sheet, never a modal
   that hides the text; loading states are a subtle inline indicator, no
   spinners over content.
6. **No engagement mechanics** — no badges, no notifications, no streaks-guilt.
   Stats exist for curiosity, shown only when asked.

## Component notes

- **Library:** cover grid, progress as a thin bottom bar on the cover, no
  percentages shouting. Empty state teaches import in one sentence.
- **Dictionary sheet:** headword, one-line translation, contextual meaning
  paragraph, source sentence quoted. Max height 40% of screen.
- **Interpretation sheet:** same pattern, scrollable, max 70%.
- **Notebook:** chronological within book, color-coded left border, note text
  in UI sans, quoted passage in body serif.
- **Settings:** one screen. API key field masked with reveal toggle.

## Accessibility

- WCAG AA contrast in both themes; dictionary/interpretation content scales
  with system font size; all touch targets ≥ 48 dp; TalkBack labels on every
  control.

## Anti-patterns (reject in review)

Decorative animation · more than one accent color · text over images ·
hamburger menus · confirmation dialogs for reversible actions · toasts that
cover text while reading.
