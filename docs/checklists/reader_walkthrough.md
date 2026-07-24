# Reader Walkthrough — Rubric R Scoring Script

> Produces the Rubric **R** score (`docs/rubric.md` §"Rubric R — Reader
> Experience"). Written for a **non-developer** running the debug APK on the
> Boox device (primary) plus one OLED Android phone (secondary, for R6's
> dark-theme/contrast cross-check). Every step below states an exact action,
> an expected result, and a pass/fail box. Total time: ~60–90 minutes.
>
> **Do not skip steps or substitute your own book selections** — R1/R3/R4
> reference specific books/words/sentences so repeated runs are comparable.
> When you finish, hand the completed score sheet (the filled-in boxes below)
> to whoever is running the build loop; they append the row to
> `loops/build/rubric_scores.jsonl` — this document does not write there
> itself.

## 0. Setup (do once)

1. Install the debug APK: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.
2. Sideload the 3 example books, translated to Slovenian, as EPUB files onto
   the device (e.g. `adb push "<file>.sl.epub" /sdcard/Download/`), then
   import each with the app's own "+" button (SAF file picker) — this is
   also the R1 import test, so don't pre-populate the library any other way:
   - **Book A** — `The New Rules of War.sl.epub`
   - **Book B** — `Active Measures....sl.epub` (Thomas Rid)
   - **Book C** — `This Is How They Tell Me the World Ends....sl.epub` (Nicole Perlroth)
3. Open a terminal with `adb` on PATH and the device connected (`adb devices`
   shows it). Keep it open for the whole walkthrough — several sections read
   `adb logcat`.
4. Have a working OpenAI or Anthropic API key entered in Settings before R3/R4
   (those sections need live LLM calls).
5. Note the app's git commit for the score row: `git -C android rev-parse HEAD`.

Score sheet — fill in as you go, total at the bottom:

| Dim | Max | Your score |
|-----|-----|-----------|
| R1 | 25 | |
| R2 | 15 | |
| R3 | 15 | |
| R4 | 10 | |
| R5 | 15 | |
| R6 | 15 | |
| R7 | 5 | |
| **Total** | **100** | |

Gates: **R1 must be ≥ 20** and **R7 must be = 5** for this build to be
release-eligible, independent of the total.

---

## R1 — Core reading reliability (25 pts, gate ≥ 20)

For **each** of Book A, B, C, run this exact sequence and note any incident.
Each crash or misrender (garbled text, missing paragraph, navigator failing
to open, position lost on reopen) is **−5**, applied once per incident up to
the full 25.

| Step | Action | Expected | Pass/Fail |
|------|--------|----------|-----------|
| 1 | Import the book via the library "+" button. | Book appears in the library grid with a cover and title within a few seconds; no error. | ☐ |
| 2 | Tap the cover to open it. | Reader opens at the first page (or the book's stored position, if reopening later). | ☐ |
| 3 | Page through 3 full chapters using the page-turn gesture (tap the edge / swipe, per the reader's configured gesture). | Every page renders correctly — full text, correct paragraph order, no cut-off lines, no blank pages between real content. | ☐ |
| 4 | Note your exact position (chapter + approx. paragraph) at the end of chapter 3, then press the device back button / home to close the app fully (swipe it away from recents). | App closes cleanly, no crash dialog. | ☐ |
| 5 | Reopen the app, tap the same book's cover again. | Reader reopens at **exactly** the position noted in step 4 (same page, same paragraph visible) — not the start of the book, not a different chapter. | ☐ |

Repeat for all 3 books (15 total step-rows). Record any incident here:

- Book A incidents (describe + step #): ______________________
- Book B incidents: ______________________
- Book C incidents: ______________________

**R1 score = 25 − 5 × (incident count), floor 0.** _________ / 25

---

## R2 — E-ink performance (15 pts — 3 criteria, ~5 pts each)

Prereq: this is the **debug** build (`PerfLog` is on by default in debug —
see `android/app/src/main/kotlin/app/berilo/reader/reader/PerfLog.kt`). If
you enabled/disabled the reader's "e-ink mode" toggle mid-session, note which
state you tested in.

### R2a — Page turn render time ≤ 150 ms (target ~5 pts)

1. `adb logcat -c` to clear the buffer.
2. Open Book A, then run `adb logcat -s BeriloPerf` in your terminal, leaving
   it streaming.
3. In the app, perform **20 page turns** at a normal reading pace.
4. Stop the logcat stream (Ctrl-C). Each turn should have printed a line like
   `page_turn_render_ms=<N>`. Collect all 20 `<N>` values.
5. Compute the median (p50) and the max.

Record: p50 = _______ ms, max = _______ ms. **Pass** if p50 ≤ 150 ms (note
the max separately — a few slow outliers don't fail this criterion, but a
p50 above 150 ms does). ☐ Pass ☐ Fail

### R2b — No ghosting-inducing animation in e-ink mode (target ~5 pts)

1. In the reader's settings sheet (gear icon in the chrome), turn **e-ink
   mode ON**.
2. Turn 10 pages, watching the screen (not the logs).
3. Expected: each page turn is an instant, full replace — no slide, no fade,
   no cross-fade between old and new page content. (Readium's e-ink
   preference disables page-turn animation; this step confirms it visually,
   since ghosting artifacts only show on the physical e-ink panel, not in a
   screenshot.)

☐ Pass (no animation observed) ☐ Fail (any slide/fade/cross-fade seen)

### R2c — Cold start to last page ≤ 3 s (target ~5 pts)

There is no `BeriloPerf` marker for app launch — measure this one with a
stopwatch or a screen recording's timestamps (there's no code instrumentation
for it; note this as a known gap if you want tighter precision later).

1. Force-stop the app: `adb shell am force-stop app.berilo.reader`.
2. Start a stopwatch the instant you tap the app icon (or the book cover, if
   testing "resume reading" cold start).
3. Stop the stopwatch the instant the **last-read page** is fully rendered
   and stable (not a loading spinner, not a blank frame).
4. Repeat 3 times, take the median.

Record: run 1 = _____ s, run 2 = _____ s, run 3 = _____ s, median = _____ s.
**Pass** if median ≤ 3 s. ☐ Pass ☐ Fail

**R2 score** = 5 × (number of the 3 criteria above that passed). _________ / 15

---

## R3 — Dictionary UX (15 pts)

Use Book A. For each row below: select the **bolded** word in the given
sentence (type or paste the sentence into the book isn't possible — instead,
find or mentally substitute a passage containing the word, or use the
in-app search if available; the important thing being tested is the
selection → "Define" flow, not this exact sentence's presence in the book),
tap **Define**, and time from tap to the sheet showing a result.

*(If the exact sentence can't be located in Book A, select the bare word
anywhere in the text and note in the "context used" column what surrounding
sentence you actually selected — the disambiguation check still applies to
whatever context you used.)*

| # | Word | Test sentence (select the bolded word) | Expected sense | Time (s) | Correct sense? |
|---|------|------------------------------------------|-----------------|----------|-----------------|
| 1 | bank | She walked along the river **bank** to clear her head before the meeting. | riverside/edge of land, not a financial institution | | ☐ |
| 2 | bank | He deposited the check at the **bank** on his way to work. | financial institution, not riverside | | ☐ |
| 3 | charge | The prosecutor read out the **charge** against the defendant. | legal accusation, not electricity | | ☐ |
| 4 | charge | Plug in the tablet overnight so the battery has a full **charge**. | electrical charge, not an accusation | | ☐ |
| 5 | spring | The old mattress had a broken **spring** poking through the fabric. | coiled metal part, not the season or a water source | | ☐ |
| 6 | spring | They hiked to the **spring** where clear water bubbled up from the rock. | natural water source, not metal or season | | ☐ |
| 7 | draft | Close the window, there's a **draft** coming through. | current of cold air, not a document | | ☐ |
| 8 | draft | I sent you the first **draft** of the report for feedback. | preliminary version of a document, not air | | ☐ |
| 9 | mine | The soldiers were warned the field might still have a **mine** buried in it. | explosive device, not an excavation or possessive | | ☐ |
| 10 | mine | The company reopened the old coal **mine** outside the village. | excavation site, not an explosive or possessive | | ☐ |

- **p50 latency** across the 10 timed lookups above: _______ s. Pass if ≤ 4 s. ☐
- **Disambiguation**: count how many of the 10 rows got the expected sense
  (check the "contextual meaning" text in the sheet, not just the headword
  translation). _______ / 10. Pass if ≥ 8/10 (allows 2 misses across the
  5-word set without failing the whole dimension).
- **Cached lookup ≤ 300 ms**: re-select word #1 (bank, sentence 1) and tap
  Define again. Expected: the sheet shows a "cached" badge and appears
  near-instantly. Time it: _______ ms. Pass if ≤ 300 ms. ☐
- **Graceful offline message**: turn on airplane mode, select any
  not-yet-looked-up word, tap Define. Expected: a plain-language network
  error message in the sheet (not a crash, not a raw exception string). ☐
  Turn airplane mode back off afterward.

**R3 score**: award up to 15, apportioned as ~6 pts latency (full 6 if p50 ≤
4s, 0 if not), ~5 pts disambiguation (5 × correct/10, capped), ~2 pts cached
lookup, ~2 pts offline message. _________ / 15

---

## R4 — Interpretation UX (10 pts)

Use Book B (*Active Measures*). Because passage text varies by edition,
locate the 5 target paragraphs by this rule instead of by page number: open
the book's table of contents, and for the chapters listed below pick **the
first paragraph in that chapter that is at least 5 lines on screen and is
not a block quote, epigraph, or heading** (skip past any front-of-chapter
quote to the first substantial paragraph of the author's own prose).

| # | Chapter (by TOC order) | Position rule | Time to result (s) | Renders readably? |
|---|--------------------------|----------------|----------------------|---------------------|
| 1 | 2nd chapter | first paragraph ≥5 lines, not a quote/heading | | ☐ |
| 2 | ~25% through the book (by chapter list position) | same rule | | ☐ |
| 3 | ~50% through the book | same rule | | ☐ |
| 4 | ~75% through the book | same rule | | ☐ |
| 5 | 2nd-to-last chapter | same rule | | ☐ |

For each: select the whole paragraph, tap **Interpret**, time from tap to
result.

- **p50 latency** across the 5: _______ s. Pass if ≤ 8 s. ☐
- **Cached**: re-run paragraph #1's interpretation a second time. Expected:
  "cached" badge, near-instant. ☐ Pass ☐ Fail
- **Long-answer readability**: for the longest of the 5 results, confirm the
  sheet scrolls smoothly, text doesn't clip or overlap the cost/cached
  footer, and line length/spacing matches the dictionary sheet's style. ☐

**R4 score**: ~6 pts latency (full 6 if p50 ≤ 8s), ~2 pts cached, ~2 pts
readability. _________ / 10

---

## R5 — Notes & highlights (15 pts — 10 scripted actions × 1.5 pts)

All in Book A, one continuous session unless step 6 says otherwise.

| # | Action | Expected | Pass/Fail |
|---|--------|----------|-----------|
| 1 | Select a sentence → tap **Highlight** → pick the amber swatch. | Sentence is highlighted inline in amber immediately (no sheet, per design guidelines "instant, quiet feedback"). | ☐ |
| 2 | Select a different sentence → tap **Note** → type a short note → Save. | A highlight + note is created; the note editor closes. | ☐ |
| 3 | Open the **Notebook** (top bar icon). | Both entries from steps 1–2 appear, grouped under the correct chapter, each with a left-border color matching its highlight. | ☐ |
| 4 | In the notebook, tap "Edit note" on the step-2 entry → change the text → Save. | The note text updates in the notebook list. | ☐ |
| 5 | Tap "Change color" on the step-1 entry → pick a different color. | The highlight's color updates both in the notebook's left border and back in the reading view. | ☐ |
| 6 | Force-close the app (`adb shell am force-stop app.berilo.reader`), relaunch, reopen the Notebook. | Both entries are still present with their edited text/color — nothing reverted or vanished. | ☐ |
| 7 | Tap an entry's text (jump-to). | The reader navigates to and displays the passage the highlight belongs to. | ☐ |
| 8 | Back in the reader, select a third sentence → tap **Highlight** only (no note). | A plain highlight (no note) is created and later appears in the notebook with no note text. | ☐ |
| 9 | In the notebook, delete one entry (trash/delete action) → confirm the dialog. | The entry disappears from both the notebook and the highlighted text in the reader. | ☐ |
| 10 | Tap the notebook's **Export** (share) icon, send to any app that opens `.md` (or a plain text viewer). | A Markdown file opens, listing the remaining entries with quoted passages in one style and notes in another, grouped by chapter. | ☐ |

**R5 score = 1.5 × (steps passed).** _________ / 15

---

## R6 — Design quality (15 pts — 12-item checklist, each pass/fail)

Walk through Library → Reader → Dictionary sheet → Interpretation sheet →
Notebook → Settings on **both** the Boox (light theme) and the OLED phone
(dark theme, `Settings → dark theme` toggle or system dark mode) before
scoring. Each item is worth 15/12 ≈ 1.25 pts.

| # | Check | Pass/Fail |
|---|-------|-----------|
| 1 | Reading text (reader body, dictionary/interpretation quoted passages) renders in the serif family; UI chrome (buttons, top bars, labels) renders in the sans family — no screen mixes the two roles. | ☐ |
| 2 | Type hierarchy reads clearly top-to-bottom (sheet headword > body > secondary/caption text) — no two adjacent styles look the same size when compared side by side. | ☐ |
| 3 | Reader margins are generous (≥24dp-equivalent) even at the minimum margin setting. | ☐ |
| 4 | A full line of reader body text at the default font size is roughly 55–70 characters (not noticeably cramped or noticeably sparse). | ☐ |
| 5 | Reader line spacing looks generous, not cramped (roughly 1.5× the text size). | ☐ |
| 6 | Exactly one accent color (deep amber) is used for interactive/primary emphasis app-wide — no stray blues/purples/greens outside the 4 highlight colors. | ☐ |
| 7 | Body and UI text are legible in bright light on the Boox (true black on true white, no washed-out gray body text) **and** equally legible in dark theme on the OLED phone. | ☐ |
| 8 | Library empty state (fresh install, no books) shows the one-sentence import instruction, not a blank screen. | ☐ |
| 9 | Notebook empty state (a book with no highlights/notes yet) shows a message, not a blank list. | ☐ |
| 10 | Dark theme is coherent across every screen visited above — no screen stuck rendering light-theme white, no purple/gray-tinted secondary text or borders anywhere. | ☐ |
| 11 | All primary tap targets (buttons, color swatches, notebook rows) are comfortably tappable without pinpoint accuracy — nothing feels sub-thumb-sized. | ☐ |
| 12 | No decorative animation while reading (page turns are instant, not slide/fade); the full-screen tap that toggles reader chrome shows no ripple flash; ordinary Material ripple on buttons/list rows elsewhere is fine. | ☐ |

**R6 score = 15 × (items passed) / 12.** _________ / 15 (need ≥ 10/12 items
passing per the project plan's S2.7 Verify line)

---

## R7 — Key & settings safety (5 pts, gate = 5)

### R7a — No key material in logs

1. `adb logcat -c` to clear the buffer.
2. Enter (or re-enter) a real API key in Settings, tap "Test key", then do
   one dictionary lookup and one interpretation lookup (R3/R4 already
   generated these — you can reuse that activity if done in the same
   session, but re-clear the buffer first if so).
3. Dump the buffer and search it for OpenAI/Anthropic key prefixes and bearer
   auth headers:
   `adb logcat -d | grep -iE "Authorization|Bearer|api[-_]?key|sk[-_]"`.
4. Expected: **zero matches**.

☐ Pass (zero matches) ☐ Fail (any match — record the log line and file a bug)

### R7b — No key material in a backup extract

The manifest sets `android:allowBackup="false"` specifically so `adb backup`
can't extract app data at all — this step proves that empirically rather
than trusting the manifest read.

1. `adb backup -f berilo_backup.ab -noapk app.berilo.reader` (confirm on the
   device screen if prompted).
2. Convert and inspect: `dd if=berilo_backup.ab bs=24 skip=1 | openssl zlib -d 2>/dev/null | tar -tv` (or use `android-backup-extractor` if available) — expect either an explicit "backup not allowed" failure, or an archive with no `app.berilo.reader` payload.
3. If a payload somehow exists, grep it for the key substring you entered.

☐ Pass (no extractable app data / no key found) ☐ Fail

### R7c — Model switch takes effect without restart

1. In Settings, note the current dictionary model, then switch it to a
   different one from the picker. Do **not** kill or restart the app.
2. Go back to the reader (still open in the background/activity stack) and
   do a dictionary lookup on a fresh word.
3. Expected: the lookup succeeds (proves the new model setting was read on
   this call, since `DictionaryRepository` re-reads settings per lookup
   rather than caching them at startup) and nothing crashes or requires a
   restart.

☐ Pass ☐ Fail

**R7 score = 5 if all three of R7a/b/c pass, else 0 (this dimension is a
release gate, not partial credit).** _________ / 5

---

## Total and sign-off

R = R1 + R2 + R3 + R4 + R5 + R6 + R7 = _________ / 100

- Gate check: R1 ≥ 20? ☐   R7 = 5? ☐
- Target: R ≥ 85 before Phase 2 is declared done (`docs/rubric.md`).
- Commit tested: `_________________`
- Date: `_________________`  Tester: `_________________`

Hand this filled-in sheet (or a photo/scan of it) to whoever runs the build
loop — they append the score row to `loops/build/rubric_scores.jsonl` per
the format in `docs/rubric.md`'s header (`{"date","rubric":"R","version",
"score","dimensions":{"R1":..,...,"R7":..},"commit","notes"}`); attach any
screenshots from R1/R6 to the tracking issue as the plan's Verify line
requires.
