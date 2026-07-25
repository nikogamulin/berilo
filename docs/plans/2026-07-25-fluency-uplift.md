# Plan — Translation fluency & quality uplift (Rubric T3/T2/T4)

> Scoped feature plan. Assignment: *"improve fluency and translation quality."*
> Owner: Supervisor (orchestrate pipeline). Rubric: **T** (`docs/rubric.md` v1.1).

---

## 1. Problem — where the points actually are

Five books scored under rubric v1.1 (`loops/build/rubric_scores.jsonl`):

| Book | T total | T2 /30 | T3 /20 | T4 /10 | T6 /5 |
|------|---------|--------|--------|--------|-------|
| New Rules of War | 88.5 | 25.95 | **13.5** | 9.08 | 5.0 |
| Active Measures | 85.0 | 24.30 | **12.6** | 9.81 | 3.25 |
| World Ends | 86.7 | 25.20 | **12.6** | 9.36 | 4.5 |
| Sandworm | 88.5 | 26.10 | **12.5** | 9.87 | 5.0 |
| Revenge of Geography | 84.9 | 24.90 | **12.4** | 7.57 | 5.0 |

**T3 fluency is flat at 12.4–13.5/20 (mean judge score ≈ 3.1–3.4 of 5) across
every book, every source format, every genre.** That is 6.5–7.6 points of
headroom — more than every other dimension combined. T2 holds ~4.5 points of
headroom (mean ≈ 4.2/5); T4 ~1 point except Kaplan's 7.6 outlier.

A dimension that is invariant across 5 different books is a **systemic**
property of the translate stage or of the measurement — not a property of any
book. Two families of cause, and we do not yet know the split:

- **Production causes.** `_TRANSLATE_SYSTEM` (translate.py) is five sentences of
  generic instruction with zero Slovenian-specific guidance and zero book-level
  register/voice context. gpt-5-mini at `reasoning_effort=low` translating
  10-paragraph batches will default to English word order, calqued idiom,
  over-explicit pronouns and English-style passive — exactly the "3 =
  understandable but awkward" band. There is no revision pass.
- **Measurement causes.** `fluency_v1` judges the target **in isolation**: no
  source, no surrounding paragraphs, no book context, and (by default) the same
  model family that produced the text. A paragraph read cold, out of context,
  plausibly scores a point lower than the same paragraph read in place. We have
  never measured the judge's own noise floor or its ceiling on *human*
  professional Slovenian prose.

**We must not guess which.** Chasing a prompt fix against an artifactual ceiling
is exactly the failure CLAUDE.md §9 records for the screen gate (four wasted
rounds). This plan therefore instruments and calibrates *before* it changes any
production prompt.

## 2. Goals and measurable gates

| # | Goal | Gate |
|---|------|------|
| G1 | Know **why** T3 sits at ~3.1/5 | Per-sample dump exists; judge noise floor and a human-prose control both measured with CIs; cause attributed to production vs. measurement in `docs/findings.md` |
| G2 | Iterate on translation quality for **≤ €0.10 and ≤ 5 min** per hypothesis | `berilo ab` reports a *paired* T2/T3 delta with bootstrap CI over ≥ 30 pairs, re-translating under **production batching conditions**, for ≤ €0.10 |
| G3 | Raise fluency | **T3 ≥ 16.0/20** on the winning variant, measured by the same paired A/B on ≥ 2 books, CI lower bound of the delta > 0 |
| G4 | Do not pay for it elsewhere | T2 delta CI lower bound ≥ −0.5 pts; T1 = 100%; T5 unchanged; cost per 100k words stays ≤ €1.50 (T7 = 5) |
| G5 | Land it | Winning variant is the default; ≥ 1 full book re-translated and re-scored; **T total ≥ 89** on that book (vs. its 84.9–88.5 baseline) |

Non-goals: changing the judge model tier as a *scoring* change (that would move
the rubric, not the product); T6/T5/T1 work; anything Android or cloud.

## 3. Sequencing

**Wave 1 (parallel, disjoint footprints, no API spend):**
- **F1 — eval instrumentation.** `berilo eval --dump <file.jsonl>` emits one row
  per judged sample (segment id, chapter, source, target, meaning, fluency), and
  `--judge-repeats N` re-judges each sample N times so intra-judge variance is
  measurable. Footprint: `berilo/eval/**`, `berilo/cli.py` (eval command only),
  its tests.
- **F2 — prompt registry + Slovenian style contract.** Extract the translate
  prompts into `berilo/prompts.py` as a *named, versioned registry*
  (`baseline_v1` = today's text, byte-identical) and add variants. `translate.py`
  takes a `style: TranslationStyle` parameter defaulting to `baseline_v1`, so
  default behavior is unchanged. Also fixes the cache-key defect in §3.1.
  **No CLI changes.** Footprint: `berilo/prompts.py` (new),
  `berilo/translate.py`, `berilo/cache.py`, their tests.

  Note: the style contract must be applied to **both** batch prompts *and* the
  `_translate_single` per-segment fallback path (`translate.py:138`), which today
  uses a separate prompt with no glossary-continuity wording — otherwise fallback
  segments silently regress to baseline quality.

### 3.1 Cache-key defect (verified BLOCKER — must land inside F2)

`cache.py:144` — `PRIMARY KEY (book_hash, segment_hash, model, lang)`. **The
prompt is not part of the cache key.** Two consequences, both silent:

- **A/B is a no-op.** Re-translating the same segments of the same book with the
  same model/lang under a variant prompt hits `get_translation` and returns the
  *control* text. The harness would report a delta of exactly zero and spend €0,
  looking like "the variant changed nothing."
- **E3 is a no-op.** Changing the default prompt and re-running a book that is
  already cached serves the old translations verbatim. The full-book run would
  cost ~€0 and score identically, and we would wrongly conclude the new prompt
  does not work.

Fix, owned by F2: add a `prompt_version` column to the `translations` primary
key, with a migration defaulting existing rows to `baseline_v1`. This preserves
all five books' existing caches (no re-billing while the prompt is unchanged)
and correctly *misses* the moment a variant or new default is used. F2's tests
must prove: (a) the same text under two prompt versions stores two rows and
returns each correctly, (b) a pre-migration database opens and its rows are
readable as `baseline_v1`, (c) a `baseline_v1` run against an existing cache
re-bills nothing.

**Wave 2 (after F1+F2 land):**
- **F3 — A/B harness.** `berilo ab <translated.epub> --variant <name>` samples K
  seeded *contiguous* runs of body-prose segments, re-translates them through the
  real `translate_book` path (real batching, real rolling context, real glossary)
  against a scratch cache, judges control vs. variant paired on T2+T3, and prints
  paired deltas with bootstrap CIs and actual cost. Footprint:
  `berilo/experiment.py` (new), `berilo/cli.py` (new `ab` command), its tests.

**Wave 3 — Supervisor-only, paid (never delegated):**
- **E1 calibration** (~€0.03): judge-repeat run on one book → intra-judge σ;
  plus a **human-prose control** — score fluency on ~30 paragraphs of
  professionally written *original* Slovenian text. If the judge scores human
  prose ≈ 3.2 too, the ceiling is the prompt's, not the translator's, and G1
  routes to a judge-prompt fix (rubric version bump, per §7 of the orchestrate
  skill's eval-artifact lane) instead of a translate-prompt fix.
- **E2 variant bake-off** (~€0.15): each F2 variant through `berilo ab` on one
  book; winner re-confirmed on a second book.
- **E3 promotion + full-book run** (~€0.8, **needs Niko's explicit go-ahead**):
  winner becomes the default prompt version, one full book re-translated and
  re-scored against G5.

## 4. Prompt variants F2 must implement (hypotheses, one per variant)

1. `sl_style_v1` — baseline + an explicit Slovenian contract: no calqued English
   word order; prefer verbal constructions over nominalizations; drop pronouns
   that Slovenian inflection already carries; avoid "s strani"/passive calques;
   use the dual where required; keep šumniki; render idioms with Slovenian
   equivalents rather than literally.
2. `book_context_v1` — `sl_style_v1` plus a one-paragraph book-level style memo
   (genre, register, narrator voice) computed once per book from the title +
   an opening excerpt, injected into every batch prompt.
3. `revise_v1` — `sl_style_v1` translation followed by a second "native
   Slovenian editor" pass over the same batch (fix fluency, meaning must not
   change). Roughly doubles calls — its cost must be reported and judged against
   G4/T7, and it is only viable if the T3 gain justifies the spend.

Each variant is a registry entry with an explicit version string, so any score
row can be traced to the exact prompt that produced it.

## 5. Risks

| Risk | Mitigation |
|------|-----------|
| The ceiling is the judge's, not the translator's — we optimize a measurement artifact | E1 runs **first** and gates E2. Human-prose control is the discriminator. §9 records this exact failure class. |
| A/B on isolated paragraphs ≠ production quality | F3 must re-translate *contiguous runs* through `translate_book` with real batch size, rolling context, and the book's cached glossary. Verify line asserts the harness calls the production path. |
| Judge noise swamps the effect | E1 measures σ; F3's Verify requires the paired-delta CI to be reported, and a variant only "wins" with CI lower bound > 0. |
| `revise_v1` blows the cost gate | F3 reports actual €/1k words per variant; G4 keeps T7 = 5 binding. |
| Cache-key collision between variants | Variant name/version must participate in the cache key or the harness must use a scratch cache — F3's tests must prove control and variant translations of the same text do not collide. |
| Prompt-registry refactor silently changes today's output | `baseline_v1` must be byte-identical to the current constants; F2's tests assert exact string equality against the pre-refactor text. |

## 6. Decisions (resolved by Niko, 2026-07-25)

- **[D-1]** E3 re-translates **The Revenge of Geography** — weakest total (84.9)
  and weakest T4 (7.57), so the largest headroom and the clearest signal.
- **[D-2]** If `revise_v1` wins but roughly doubles cost, it ships as an
  **opt-in `--revise` flag**, not the default. The default stays cheap and T7
  stays at full marks.
- **[D-3]** E1's human-prose control uses a **public-domain Slovenian text**
  fetched at run time (e.g. Cankar via Wikivir), with the exact source recorded
  in `docs/findings.md`. Known caveat to note when interpreting: older literary
  register differs from modern non-fiction, so the control bounds the judge's
  ceiling generously rather than exactly.
