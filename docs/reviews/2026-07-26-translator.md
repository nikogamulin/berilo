# Translator pipeline — code review

Scope: `translator/berilo/**` — extraction/normalization → glossary → translate →
cache → assemble → providers/cost → eval/experiment → CLI.

Each finding lists a file:line anchor, a one-line defect statement, and a concrete
failure scenario. **CONFIRMED** = the code path was traced end to end; **PLAUSIBLE**
= the defect depends on input data or configuration that was not directly observed.

---

## HIGH — silent data loss or wrong output

### 1. Numeric single-token titles and lines are silently dropped — CONFIRMED
`pdf.py:835` (`_is_droppable`) and `pdf.py:739` (`_strip_page_furniture`)

Both use `_SINGLE_TOKEN_WITH_DIGIT_RE = ^\S*\d\S*$` to remove endnote-marker
artifacts, but the pattern matches *any* whitespace-free token containing a digit.

Failure: a chapter titled `"1984"` / `"1917"`, or a body line `"COVID-19"` / `"3D"`.
`_looks_like_heading` accepts it, then `if not _is_droppable(title)` at `pdf.py:985`
is False, so the heading block is never created and the chapter title is lost.
Two independent drop paths exist (margin-band furniture strip + heading admission).

### 2. Malformed XHTML drops an entire spine document — CONFIRMED
`epub.py:652-656` (same fragility at `_parse_ncx_titles:211`, `_parse_nav_titles:231`,
`_find_opf_path:127`)

Spine documents are parsed with strict `ET.fromstring`. Any not-well-formed XHTML
raises `ParseError`, which is caught and the whole document is `continue`d — every
paragraph, heading, and image in it is dropped with only a warning log.

Failure: an EPUB whose chapter 3 XHTML contains a bare `&nbsp;` (no entity
declaration), an unclosed `<br>`/`<img>`, or a stray `&` — all extremely common.
Chapter 3 is absent from `Book.segments` and the loss is a log line, not an error.

### 3. Glossary is injected into every prompt but is absent from the cache key — CONFIRMED
`cache.py:77` (translation primary key), `cache.py:225` (glossary primary key)

Translation key: `(book_hash, segment_hash, model, lang, prompt_version)` — no
glossary component. Glossary key: `(book_hash, model, lang)` — no `prompt_version`,
and `_GLOSSARY_SYSTEM` (`glossary.py:38`) plus the sampling constants are unversioned.

Failure: improve the glossary prompt or sampling to fix a mistranslated proper name,
then re-run. The glossary cache hits on the unchanged `(book, model, lang)` key and
returns the old terms; already-cached translations are served under the unchanged
translation key and never re-sent. The "improvement" reports no change at €0 while
the model was never called — a null result indistinguishable from a real one.

### 4. Default translation style hardcodes Slovenian regardless of target language — CONFIRMED
`prompts.py:68-116` and `prompts.py:240` (`DEFAULT = REVISE`); `config.py:20`
(`DEFAULT_TARGET_LANG = "sl"`, overridable via `BERILO_TARGET_LANG`)

The shipped default style embeds `_SL_CONTRACT` and a `_REVISE_SYSTEM` that reads
"You are a native Slovenian editor" with Slovenian-specific rules (šumniki, dual,
clitic cluster). Nothing ties the style to `target_lang`.

Failure: `berilo translate book.epub --to de`. The user message correctly injects
"Translate into: de", but the system prompt simultaneously drives Slovenian
information structure and runs a "native Slovenian editor" revision pass over the
German draft — contradictory language instructions, billed at the ~2.9× two-pass
`revise_v1` cost. Currently masked because the only target in use is Slovenian.

---

## MEDIUM — cost visibility and robustness

### 5. Reported run cost excludes the content-policy fallback spend — CONFIRMED
`cli.py:195` and `cli.py:216` vs `translate.py:853`

`_CostTrackingClient` wraps only the primary `client`; `fallback_client` (`cli.py:164`)
is passed raw into `translate_book`. `stats.cost_eur` correctly includes fallback
batches (`translate.py:853`), but the CLI summary overrides the printed total with
`tracked_client.total_cost_eur`, which never sees the fallback client's calls.

Failure: an OpenAI-moderated batch is retried via Anthropic. Its spend is real but
absent from the "€ total (incl. glossary)" line — the printed total under-reports.
Triggers only when a content-policy refusal actually occurs.

### 6. A model outside the pricing table crashes after the API is already billed — CONFIRMED
`pricing.py:43-48` (raises `ValueError` for unknown models); `providers/__init__.py:54-81`
(`create_client` accepts any `gpt-*`/`o*`/`claude-*`); `openai.py:134` / `anthropic.py:119`
(`cost_eur(...)` is called after the SDK response, i.e. after billing)

Failure: set `BERILO_TRANSLATION_MODEL=gpt-4.1` (or any model not in the 6-entry
dict). Routing and the API call both succeed and are charged, then `cost_eur` raises
`ValueError` and the completion is lost — per call, mid-book. The "select any model"
contract and the pricing table are out of sync, and the check lands after the spend.

### 7. Providers return empty text silently on content-less responses — CONFIRMED
`openai.py:127` (`... .content or ""`), `anthropic.py:110-112` (join of text blocks only)

Both coerce a content-less response to `""` with no error while still reporting
non-zero tokens and cost.

Failure: a gpt-5-class reasoning model exhausts a small `max_tokens` on hidden
reasoning and returns `content=None` with `finish_reason="length"`; or the model
returns only non-text blocks. `complete()` returns an empty, billed translation.
Whether this is caught depends entirely on the downstream 1:1 marker check.
Related: Anthropic's default `max_tokens=1024` (`anthropic.py:24`) is never checked
against `stop_reason`, so a long batch can be silently truncated.

### 8. Experiment per-word cost folds in the fixed memo cost, inflating T7 — CONFIRMED
`experiment.py:1020` (`translation_cost_eur = stats.cost_eur + memo_cost`);
`experiment.py:552-563` / `566-568` (rate properties)

`eur_per_1k_words`'s own docstring states per-book fixed costs (glossary, style memo)
are excluded so the figure tracks the marginal per-word rate — but the memo cost is
inside `translation_cost_eur`.

Failure: a book-context variant whose memo is not yet cached charges a one-time
~€0.02 book-opening call against ~900 judged words → reported €3.3/100k, reading as
a T7 failure, when the true marginal rate (~€1.1/100k) passes. `total_cost_eur` is
correct; only the two rate properties misuse the field.

### 9. Cache prompt-version migration is not crash-atomic — PLAUSIBLE
`cache.py:190-202`

The `INSERT ... SELECT` into `translations_migrating` commits inside `with self._conn:`,
but the subsequent `DROP TABLE translations` and `ALTER TABLE ... RENAME`
(`cache.py:201-202`) run as two separate autocommitted statements outside any
transaction.

Failure: the process is killed between the `DROP` and the `RENAME`. On next open,
`PRAGMA table_info(translations)` returns no columns, `_migrate_prompt_version`
returns early, and `_init_schema` creates a fresh empty `translations`. The migrated
rows sit orphaned in `translations_migrating` and are never recovered — the cache for
every previously translated book is lost, contradicting the docstring's guarantee.

---

## LOW / edge-triggered

### 10. `context_pairs=0` never trims, feeding the whole book as rolling context — CONFIRMED
`translate.py:763-766`

`_remember` trims only under `if context_pairs > 0 and len(recent_pairs) > context_pairs`.
Passing `0` (the natural way to disable context) leaves `recent_pairs` untrimmed, so
each batch is built with every prior source/target pair. Latent today (the default is
non-zero), but the value `0` produces the opposite of its intent and would blow the
per-batch token ceiling on a large book.

### 11. T7 actual-cost query ignores `model` — PLAUSIBLE
`runner.py:100-105` (`read_cache_facts`)

The glossary query filters by `book_hash AND model AND lang`, but the cost query on
the same rows drops `model`: `SUM(cost_eur) ... WHERE book_hash = ? AND lang = ?`.

Failure: a book translated first with an expensive model, then re-run with
`gpt-5-mini`. Evaluating the mini output sums cost across both runs, so T7 charges the
mini translation with the expensive run's spend and its per-100k figure inflates.
Harmless only if `calls` is guaranteed to hold a single model per book/lang.

### 12. Experiment lead-in forbidden-hash guard is per-run, not global — PLAUSIBLE
`experiment.py:300` (per-run `hashes`), `experiment.py:258` (guard), `experiment.py:808`
(lead-in stored under `variant_version`)

`candidate_runs` builds the forbidden-hash set from the current run only. A run's
lead-in is not checked against other runs' judged segments, yet all lead-ins are stored
under `variant_version`.

Failure: an identical paragraph appears as a judged segment of run 0 and as run 1's
rolling-context lead-in. Same `segment_hash`; run 1's lead-in is written under
`variant_version`, so run 0's judged segment hits it and is served the control
translation at €0 instead of being re-translated. That pair's variant equals its
control, its T2/T3 delta is exactly 0, and no error is raised.

### 13. Extraction screen defaults every unparseable reply to "dirty" — PLAUSIBLE (low)
`screen.py:206-208` (`_is_yes`), used at `screen.py:233`

`_is_yes` returns True only when the reply starts with "yes"; empty output, an API
hiccup, or a hedged "Probably clean…" all count as `is_clean=False` and fold into
`clean_fraction`. `judge.py` deliberately raises on unparseable verdicts instead.
NO is the safe default for a cleanliness screen, so bias is one-directional (down) and
low severity, but it is a silent default on parse failure.

### 14. Numbered-response markers match `[[n]]` anywhere in the reply — PLAUSIBLE
`translate.py:299-304` (`_MARKER_RE` / `parse_numbered_response`)

Markers are scanned across the whole reply with no line anchoring.

Failure: translation 2 of a 3-segment batch contains "element `[[2]]` of the array".
The stray marker is parsed as a second index 2; `len(matches)` becomes 4 while
`len(parsed)` stays 3, raising `ValueError` and forcing a needless strict retry /
per-segment fallback (extra cost).

### 15. Cross-chapter image anchor silently drops the image — PLAUSIBLE
`assemble.py:166-182`, `assemble.py:281-287`

`_group_images` groups a chapter's images by `anchor_segment_id`, but `anchored_images`
emits them only when a segment with that id is rendered in this chapter (plus the
top-of-chapter `None` bucket). A non-`None` anchor pointing at a segment not present in
its own `chapter_index` is emitted nowhere and vanishes with no error. Requires
inconsistent anchor data.

### 16. EPUB image dedup is book-scoped — PLAUSIBLE
`epub.py:678-682` (`seen_image_paths`)

Only the first reference to a resolved image path is carried. Intended for
logos/watermarks, but a figure intentionally reused in two chapters (or referenced
again in an appendix) is carried once and anchored only to its first occurrence; the
second placement is lost. The PDF path's analogous cut requires ≥3 occurrences; the
EPUB path drops on the 2nd.

### 17. EPUB continuation documents reuse the title but still increment `chapter_index` — PLAUSIBLE
`epub.py:697-701` vs `epub.py:755`

A spine doc with no TOC entry of its own reuses `previous_title` (treated as a chapter
continuation), but `chapter_index += 1` fires unconditionally. A chapter split across
N XHTML files (common Calibre output) yields N distinct `chapter_index` values sharing
one title, so `chapter_count` over-counts and `chapter_index` is no longer 1:1 with
`chapter_title`.

### 18. Anthropic never raises `ContentPolicyError` — PLAUSIBLE
`anthropic.py` (no equivalent to `openai.py:119-125`; does not import the type)

`base.py:66-73` documents `ContentPolicyError` as the provider-agnostic signal for
routing a refused batch to a fallback. Only OpenAI raises it, so an Anthropic
content-policy refusal propagates the raw SDK error and aborts the run. The fallback
path works one direction only.

### 19. `create_client` routes any name starting with `"o"` to OpenAI — PLAUSIBLE (minor)
`providers/__init__.py:32` (`_OPENAI_MODEL_PREFIXES = ("gpt-", "o")`)

The bare `"o"` prefix matches any string beginning with `o` (e.g. a typo `"opus-4"`,
`"omni"`), routing it to OpenAI rather than failing as unrecognized. Combined with
finding 6 this surfaces as a confusing OpenAI-side error or a pricing crash.

### 20. Empty book-context memo is not cached, re-billing on every resume — MINOR
`translate.py:643-645` (`build_book_context`)

Only a non-empty memo is cached; the empty path returns without storing anything, so a
killed-and-resumed run repeats the derivation call. Rare, but contradicts the "resumed
run neither re-bills the memo call" guarantee.

---

## Assessed clean (no logic defect found)

- **`sampling.py`** — samples without replacement with a freshly-seeded RNG; bootstrap
  resamples *with* replacement `n` of `n`; correct 0.025/0.975 percentile tails;
  empty-input and `n≤0` paths guarded.
- **`judge.py`** — strict `SCORE:` regex, range-checked, retries once then raises;
  never silently defaults.
- **`rubric_t.py`** — point estimates and their bootstraps are mutually consistent;
  T2/T3 use `seed`, T6 uses `seed+1`; all divisions guarded.
- **`models.py`** — `field(default_factory=list)` (no mutable-default bug), frozen
  `Segment`/`ImageResource`, correct enum round-trip, collision-free segment IDs
  (`position` is globally monotonic).
- **`mobi.py`, `normalize/__init__.py`, `evaluate.py`** — sound.
- **`retry_with_backoff` (`providers/__init__.py:84-118`)** — bounded (1 + 5 retries),
  no infinite loop, no token double-counting.
- **`pricing.py` math** — per-million scaling, input/output split, and USD→EUR correct;
  raises rather than defaulting to €0 (see finding 6 for the timing caveat).
- **`config.py` precedence** — default < env < `.env` < overrides, consistent with the
  docstring; keys `repr=False`; no `os.environ` mutation. (One edge case: a
  `reasoning_effort=""` override survives the filter and would be sent verbatim;
  `reasoning_effort` also cannot be reset to the API default via config —
  `config.py:34,57,101` / `openai.py:110`.)

## Notes on scope

- `doctor` (`doctor.py:44-47`) smoke-tests the provider *default* models, not the user's
  configured `translation_model`/`judge_model`, so it will not surface finding 6 for the
  model actually in use. Consistent with its ~€0 intent, but gives false confidence.
- Multi-column PDFs would be mis-ordered by the pure `(y0, x0)` line sort
  (`pdf.py:444`), but the module explicitly assumes single-column body text, so this is
  not counted as a defect.

---

## Highest-priority fixes

1. **Finding 1** — real books lose chapters today (numeric titles).
2. **Finding 3** — glossary absent from the cache key silently poisons every
   glossary/prompt experiment as a €0 no-op.
3. **Finding 2** — a single malformed chapter drops silently from the canonical input
   format.
