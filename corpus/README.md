# The evaluation corpus

Two fixed sample books, cut from the reference library, for measuring whether a
change to the translator made translation better or worse — **in minutes, for
cents**, instead of hours for euros.

Read this before adding a feature, changing a prompt, touching normalization, or
claiming a quality delta.

---

## 1. Why this exists

Rubric T scores a *translated book*. Doing that properly costs €0.60–€1.45 and
tens of minutes per book, which is right at a milestone and useless inside a
build loop. So the loop gets skipped, and changes land on the argument that they
*ought* to help.

These samples make the measurement cheap enough to actually run:

| Tier | Paragraphs | Source words | Cost per language | Wall clock |
|---|---|---|---|---|
| `smoke` | 40 | 2,733 | **€0.036** | ~1 min |
| `standard` | 150 | 11,217 | **€0.125** | ~3 min |

Measured, not estimated — `berilo translate … --dry-run` at `gpt-5-mini`, the
default two-pass style. All five target languages, `standard` tier: **€0.62** a
sweep. Add ~€0.03 per language for `berilo eval`'s judge calls.

Single-pass `--style baseline_v1` roughly halves it (`smoke` €0.018,
`standard` €0.061) but measures a different pipeline than the one users get.
Use it for a fast structural check, never for a quality number.

## 2. Use them

The samples are ordinary EPUBs. Every existing command works on them unchanged;
there is no special mode to learn.

```bash
# once per machine — the reference books live outside this repo
export BERILO_REFS_DIR=../refs        # default; the workspace checkout's refs/
PYTHONPATH=translator python3 -m berilo.eval.corpus build

# the loop: translate the smoke sample, score it
berilo translate corpus/build/berilo-sample-smoke.epub --to sl -y
berilo eval corpus/build/berilo-sample-smoke.sl.epub --sample 40 --seed 42
```

`--dry-run` first if you have changed anything that affects batching or model
choice; it costs nothing and prints the estimate you should recognise from §1.

### Which tier

| Situation | Tier |
|---|---|
| Any change to `translate.py`, `prompts.py`, `normalize/`, `assemble.py` | `smoke` — before you open the PR |
| A change you intend to describe as improving quality | `standard`, both before and after |
| A change to batching, markers, or the cost gate | `smoke` across **all five** languages |
| A score you are going to write into `rubric_scores.jsonl` or a PR body | `standard` |

**Never quote a `smoke` score as a quality result.** Forty paragraphs give a
bootstrap CI wide enough to swallow most real deltas. It answers "did I break
something", not "is this better".

### The five languages

`sl de es it pt` — Slovenian is primary and the rest are the locales
`berilo-cloud` ships. The source side is identical for all of them, so one build
serves every language.

```bash
for L in sl de es it pt; do
  berilo translate corpus/build/berilo-sample-smoke.epub --to "$L" -y
done
```

Style resolution is per language and automatic: `sl` gets `revise_v1`,
everything else `revise_generic_v1`. A style bound to another language is
refused rather than silently substituted, so if you pass `--style sl_style_v1
--to de` you will get an error — that is the A3 language-binding rule working,
not a bug.

**Rubric T's judge prompts name Slovenian explicitly** ("correct šumniki"). T2
and T3 on a non-`sl` output are therefore *not* valid Rubric T scores. Use the
other four languages for structural and cost checks (T1, T5, T7) and for reading
the output yourself. Scoring them properly needs per-language judge prompts,
which do not exist yet.

## 3. What is committed, and what is not

```
corpus/
  README.md               this file
  manifest-smoke.json     committed — the selection, derived values only
  manifest-standard.json  committed
  build/                  GITIGNORED — the built sample EPUBs and translations
```

The reference books are **copyrighted**, and this repository is public and
permanent. So the prose never enters it. The manifests record only what can be
derived: book hashes, chapter and position indices, segment hashes, word counts,
length bands, and stratum labels. A test enforces that no segment text reaches
them (`translator/tests/test_corpus.py::TestManifest::test_carries_no_book_text`).

The manifests also carry each book's title and author as an ordinary citation —
a bibliography, not a reproduction. Download-site markers are stripped from
those titles: `_OceanofPDF.com_…` and `(z-library.sk, …)` say where a file came
from, which does not belong in a public repository.

**`refs/` itself is gitignored in the workspace repo**, and `*.pdf` / `*.epub`
are gitignored here. Do not defeat either.

## 4. Rebuild and verify

```bash
PYTHONPATH=translator python3 -m berilo.eval.corpus build    # rebuild both tiers
PYTHONPATH=translator python3 -m berilo.eval.corpus verify   # check the manifests hold
```

`build` is **deterministic**: the same books and seed produce byte-identical
EPUBs and identical manifests. That is the whole basis for comparing a score
before and after a change, so if a rebuild produces a diff, something moved and
you need to know what before you trust any number.

`verify` re-normalizes the reference books and checks that every selected
segment still exists at its recorded coordinates with its recorded hash. It
catches two things:

1. A reference file was replaced or edited.
2. **`normalize` changed.** This is the one that matters. A change to segment
   boundaries silently repoints every selection at different prose while the
   manifest's hashes sit there looking correct. `verify` turns that into a
   message naming the segments that moved.

So: **after any change to `normalize/`, run `verify`.** If it reports drift,
that is a finding about your change, not a stale file to regenerate past. Decide
whether the new boundaries are right, *then* rebuild and note in the PR that the
corpus moved — a score compared across a rebuild is comparing two samples.

## 5. What is in the sample, and why

Selection is stratified, not uniform. At 40 paragraphs a uniform draw would miss
the rare features entirely — `hyphen_break` occurs in 0.5 % of eligible
paragraphs, so a uniform sample of 40 misses it about four times in five, and
the sample would quietly stop testing whether the translator copes with a word
split across a line break.

**Six books**, floored so a big one cannot crowd out a small one, spanning
history and disinformation, causal inference, complex systems, systems thinking,
cyberwarfare, and applied ML security. Five PDF, one EPUB.

**Three length bands** — short (15–34 words), medium (35–79), long (80+). Long
paragraphs stress batching and marker alignment; short ones stress marker
*density*, since more fit in one request.

**Ten strata**, each guaranteed a floor (2 for `smoke`, 6 for `standard`), with
what each one provokes:

| Stratum | Corpus prevalence | What a failure looks like |
|---|---|---|
| `digits` | 52.7 % | a quantity mistranslated — a factual error the prose gives no clue about |
| `non_ascii` | 71.3 % | accented names or curly quotes mangled |
| `acronym` | 28.7 % | NATO translated, expanded, or case-folded |
| `quotes` | 30.2 % | wrong quotation marks (Slovenian uses „…“) |
| `parenthetical` | 25.1 % | an aside dropped or reordered |
| `year` | 19.4 % | a date "helpfully" localised |
| `dash` | 15.2 % | em dash flattened to a hyphen or dropped |
| `citation` | 3.0 % | `[12]` or `(2001)` not surviving verbatim |
| `colon_lead` | 1.5 % | a colon that introduces the next paragraph, lost |
| `hyphen_break` | 0.5 % | `informa- tion` — a PDF artifact the translator must cope with |

Front and back matter are excluded, for the reason Rubric T v1.1 records:
pooling Notes and index fragments as prose inflates denominators and measures
the extractor rather than the translation.

Each manifest's `coverage` block records what was achieved, and
`coverage.unmet_strata` names any floor that could not be filled. Both tiers
currently meet every floor.

### What the sample does *not* cover

State these before quoting a score, because they are the sample's blind spots:

- **No fiction, dialogue, or verse.** The whole corpus is English expository
  non-fiction. Nothing here tests register shifts, voice, or dialogue
  attribution.
- **No lists or blockquotes.** Not a choice — `normalize` produces no
  `LIST_ITEM` or `BLOCKQUOTE` segments from any of these six books, so Rubric
  T5's list and blockquote preservation is untested by this corpus.
- **Extraction is not tested.** The sample is an EPUB, so T6 auto-scores 5 even
  though most of the prose came from PDFs. The artifacts are baked into the
  text, which is why `hyphen_break` is a stratum, but to test *extraction* run
  `berilo inspect <a real PDF in refs/> --screen` against the original.
- **Only English source.** No source language other than `en` is exercised.
- **T4 (terminology) is weak here.** Glossary terms are built per book, and a
  sample of six excerpts has far fewer repeated domain terms than a whole book.

## 6. Adding a book, a stratum, or a tier

All three are edits to `translator/berilo/eval/corpus.py`, and all three
**change the sample**, so scores from before and after are not comparable. Say
so in the PR.

- **A book** — drop it in `refs/`. It is picked up automatically; nothing in
  the code names a filename. Rebuild, and `verify` afterwards.
- **A stratum** — add it to `STRATA` with a comment saying what failure it
  provokes. A stratum that does not name a failure mode is decoration. Add a
  detection case to `TestStrata::test_every_stratum_is_detectable`, which
  asserts every stratum is matchable — a stratum nothing can match is a
  silently dead quota.
- **A tier** — add it to `TIERS`, `STRATUM_FLOOR` and `BOOK_FLOOR`.
  `TestTierShape::test_every_tier_has_both_floors` fails otherwise.

Do not change `SEED` to "get a better sample". The seed's only job is to not
move.

## 7. Where this fits

| | |
|---|---|
| Builder | `translator/berilo/eval/corpus.py` |
| Tests | `translator/tests/test_corpus.py` |
| Rubric T | [`../docs/rubric.md`](../docs/rubric.md) — the scoring procedure these samples feed |
| Scoring harness | `translator/berilo/eval/` — `berilo eval` itself |
| Reference books | `$BERILO_REFS_DIR`, default `../refs` (outside this repo) |

The samples are an **evaluation** artifact, not a contract. They do not belong
in `contracts/`: nothing in another repository is bound by them, and no port has
to agree with them. Conformance vectors — which *are* binding — live in
`contracts/vectors/` and are a different thing entirely.
