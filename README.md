# Berilo

**Read books in your own language.**

🇸🇮 **Slovensko:** [README.sl.md](README.sl.md) — celotna navodila v slovenščini.

<p align="center">
  <img src="assets/social/berilo-hero.png" alt="Berilo — from a wish for a Slovenian translation to a finished e-ink book, via an agent loop of hypothesis → build → review → verify → keep or fix" width="620">
</p>

Berilo translates books (PDF / EPUB / MOBI) into your language using inexpensive
LLMs — built to preserve meaning, not just words — and gives you a reading app
made for people who actually read: an in-context LLM dictionary, interpretation
of dense paragraphs, highlights and notes.

Five books have gone through the pipeline end to end, English → Slovenian, at
€0.60–€1.45 each. Translation quality is scored, not asserted: 85.0–88.5 on a
100-point rubric with bootstrap confidence intervals.

## What is in this repository

**The translator**, and the contracts every Berilo client implements. This is
the reference implementation of the translation core: `berilo-cloud` imports it
as a package rather than reimplementing it, and the reader apps are ports held
to it.

The **reader apps are not here.** They live in separate, private repositories —
`berilo-android` and `berilo-ios` — and are not open source. Everything below
describes the translator CLI unless it says otherwise.

## Is the translator free?

**Yes.** The translator is MIT-licensed software. There is no account, no
subscription, no Berilo server, and no telemetry. You run it on your own
machine, against your own LLM provider.

You pay one party, and it is not us: **your own LLM provider**, per token, at
their list price. A full book costs roughly €1. See
[What it costs](#what-it-costs).

### Your API key stays with you

| Where | Storage | Leaves the device? |
|-------|---------|--------------------|
| Translator CLI | `.env` in the project folder (gitignored) | Never — only to the provider's API |

Concretely:

- The key goes from your `.env` / your phone straight to `api.openai.com` or
  `api.anthropic.com`. There is no middleman, because there is no Berilo backend.
- **Your books never leave your machine.** Only the text segments being
  translated are sent to the provider — the same as pasting a paragraph into a
  chat window, one batch at a time.
- Keys are never logged. In the CLI, `Config` marks the key fields `repr=False`,
  so they are omitted from anything that prints or logs the config. In the app,
  tests assert that an auth failure's error message *and* its cause contain no
  key material.
- Set a spend cap in your provider's dashboard if you want a hard ceiling. Berilo
  also refuses to start a paid run without showing you the estimate first.

### Getting a key

**OpenAI** (default, cheapest for this job) — <https://platform.openai.com/api-keys>
→ *Create new secret key* → top up a few euros of credit → paste into `.env` as
`OPENAI_API_KEY`.

**Anthropic** (alternative) — <https://console.anthropic.com/settings/keys>
→ *Create Key* → paste into `.env` as `ANTHROPIC_API_KEY`.

One provider is enough. Default model is `gpt-5-mini`; every model is
user-selectable with `--model`.

## What it costs

Measured full-book runs, English → Slovenian, `gpt-5-mini`, reasoning effort
`low`. Rubric T is the translation-quality score (0–100) with a 95 % bootstrap CI.

| Book | Source | Words | Style | Cost | Rubric T |
|------|--------|-------|-------|------|----------|
| The New Rules of War | EPUB | 82 k | `baseline_v1` | €0.60 | 88.5 [86.0, 90.8] |
| Sandworm | EPUB | 105 k | `baseline_v1` | €0.75 | 88.5 [86.2, 90.5] |
| The Revenge of Geography | EPUB | 124 k | `revise_v1` (default) | €1.45 | 88.0 [86.2, 89.9] |
| Active Measures | PDF | 144 k | `baseline_v1` | €1.01 | 85.0 [82.6, 87.5] |
| This Is How They Tell Me the World Ends | scanned PDF (OCR) | 182 k | `baseline_v1` | €1.22 | 86.7 [84.3, 89.0] |

Normalized, at ~300 words per printed page:

| | per 100 pages (~30 k words) | per 100 k words | typical 350-page book |
|---|---|---|---|
| **`revise_v1`** — default, adds a native-editor second pass | **≈ €0.35** | ≈ €1.17 | **≈ €1.20** |
| `baseline_v1` — single pass, `--style baseline_v1` | ≈ €0.20 | ≈ €0.70 | ≈ €0.75 |

The default costs about 1.7× the single pass and buys a measured +4.1 points of
Rubric T on a full book (83.9 → 88.0, near-disjoint CIs). Cheap either way.

Other spend:

- `berilo translate --dry-run` — **€0.00.** Prints the per-chapter token estimate
  and total before anything is billed.
- `berilo doctor` — one sentence, ~€0.0001.
- `berilo eval --sample 40` — ~€0.15 per scoring run.
- **Re-runs are free.** Every translated segment is cached, keyed by book,
  segment, model, language *and* prompt version. Interrupt a run and restart it:
  you pay only for what wasn't done.

Prices as of 2026-07 (`translator/berilo/providers/pricing.py`, USD→EUR 0.92).
Verify against your provider's current rates before relying on absolute figures.

## Status

| Phase | What | Status |
|-------|------|--------|
| 1 | Translator CLI (`translator/`) — book in, translated EPUB out | working; 5 books shipped, Rubric T ≥ 85 on all |
| 2 | Android reader — offline, e-ink friendly (Boox) | moved to the private `berilo-android` repository |
| 3 | Cloud sync + web note review (closed-source service) | planned |

Plan and objective acceptance criteria: [`docs/project_plan.md`](docs/project_plan.md).
Quality rubrics the project optimizes: [`docs/rubric.md`](docs/rubric.md).

## Translator CLI (Phase 1)

```bash
cp .env.example .env             # add your OpenAI or Anthropic key

cd translator
python3 -m venv .venv            # a venv, not a system install — see below
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -e .

berilo doctor                    # provider smoke test, one sentence, ~€0
berilo inspect mybook.epub       # extraction preview, no API cost
berilo translate mybook.epub --to sl --dry-run   # cost estimate — always run first
berilo translate mybook.epub --to sl             # translated EPUB alongside the source
berilo eval mybook.sl.epub --sample 40 --seed 42 # quality score with a CI (~€0.15)
```

Useful flags:

| Flag | Effect |
|------|--------|
| `--to sl` | Target language (default from `.env`, `sl`) |
| `--dry-run` | Estimate cost, make zero API calls |
| `--style baseline_v1` | Single-pass, ~40 % cheaper, measurably less fluent |
| `--model gpt-5` | Any model in the pricing table |
| `--bilingual` | Emit a source + target EPUB |
| `--skip-back-matter` | Pass index/notes/bibliography through untranslated |
| `--concurrency 4` | Batches translated at once. `1` is strictly sequential |
| `--mt-draft` | Draft with Google Translate, post-edit with the LLM (see below) |
| `--batch-size 20` | Segments per API call |
| `-o out.epub` | Output path |

Requires Python 3.10+. MOBI input additionally requires Calibre (`ebook-convert`).

**Use a virtualenv rather than a system install.** Recent Homebrew and Linux
distribution Pythons are "externally managed" and will refuse `pip install -e .`
outright; worse, if the refusal is worked around, `make test` and the `berilo`
console script can end up resolving to a *different* interpreter than the one
the package landed in, so the suite reports import errors that look like bugs in
the project. The venv above avoids both. Keep it activated for `make test`.

### How long a book takes, and what the two speed flags do

A full-length book is a few thousand segments. The translator sends them in
**waves**: `--batch-size` segments share one API call, and `--concurrency`
calls are in flight at once.

Both defaults are measured rather than guessed. On `gpt-5-mini`, twenty segments
per call bills about a third of the output tokens per segment that ten does,
because a reasoning model's hidden budget is charged **per call** and a bigger
batch amortizes it — so the larger batch is both faster and cheaper. Four lanes
then multiply throughput at an identical call count.

Measured on the standard sample corpus, 150 paragraphs of real book prose, each
arm with its own cache:

| | wall clock | Rubric T |
|---|---|---|
| 1 lane, batch 10 (the old default) | 938 s | 91.9 [90.0, 93.8] |
| **4 lanes, batch 20** (today's default) | **234 s** | **92.1** [90.3, 93.9] |

**4.01× faster, and the score did not move** — +0.2 with almost entirely
overlapping confidence intervals. That mattered because the batch-size half was
*not* safe by construction: it changes how much text shares one prompt and one
reasoning budget. Concurrency alone is safe by construction (identical prompts,
identical call count), so had the score dropped, the two would have shipped
separately. It didn't, so they didn't.

The one thing concurrency trades away is context freshness. Each batch prompt
carries the previous batches' translations so voice and terminology stay
continuous, and a wave takes **one snapshot before it runs**, shared by every
lane — so staleness is bounded to `concurrency × batch-size` segments and never
depends on which call happens to return first. Terminology does not drift,
because that is carried by the per-book glossary, which is built once up front
and injected into every prompt.

If you want the old strictly sequential behaviour — for a reproducible
comparison, or a provider with a tight rate limit — use `--concurrency 1`. Runs
are deterministic either way.

### Machine-translation drafts (`--mt-draft`), and why they cost more

With `--mt-draft`, Google Cloud Translation produces a first draft and the LLM
**post-edits** it instead of translating from scratch. The editor pass already
takes source plus draft, so this *replaces* the LLM's drafting call rather than
adding to it: one LLM call per batch where a two-pass style makes two.

```bash
# add GOOGLE_TRANSLATE_API_KEY to .env first
berilo translate mybook.epub --to sl --style revise_v1 --mt-draft --dry-run
```

It requires a revising style. A draft nobody edits is raw machine translation,
so `--mt-draft` with a single-pass style is refused rather than silently
producing something else.

**Read the price before enabling it.** Cloud Translation v2 Basic lists at
**USD 20 per million characters** (verify — vendor pricing moves), with the
first 500k characters a month free, which is under one book. A ~130k-word book
is roughly 715k characters:

| | Cost for one book |
|---|---|
| Two-pass LLM (`revise_v1`, `gpt-5-mini`) | ~€1.45 |
| `--mt-draft` — Google | **~€13** |
| `--mt-draft` — LLM half | ~€0.73 |

So machine translation here is the **expensive** option, roughly nine times the
cost of the entire LLM pipeline it assists. It halves LLM calls, but Google's
per-character bill dwarfs the saving.

**What is measured so far, on the smoke corpus (46 segments of real book
prose):** Google's drafts came back **100 % complete** (46/46, none empty) with
**100 % inline-markup retention** (10/10 `em`/`strong`/`i`/`b` tags, and all 4
marked-up segments preserved exactly) at **16 ms/segment** — about 44× faster
than the LLM's drafting call. Measured spend was €0.3173 for 17 242 characters,
which extrapolates to ≈ €13.15 for a 715k-character book and is where the table
above comes from.

So the *structural* risk of machine translation — dropped segments, mangled
italics — did not materialize. What is **not** yet measured is the part that
matters most: whether post-editing a Google draft scores better or worse on
Rubric T's meaning (T2) and fluency (T3) dimensions than translating cold. Those
need a judge model, and until that run happens this feature is a well-built
hypothesis.

That means it has to earn its place on **quality**, not price — and the case
for it is real but unproven: Google's Slovenian is strong, cheap LLMs are
comparatively weak on low-resource targets, and post-editing a good draft is
a different task from translating cold. Whether that shows up as a Rubric T
gain is a measurement, not an opinion:

```bash
# price both arms, make zero calls
python3 -m berilo.eval.verify --what mt --dry-run

# run both arms, each with its own cache, and score both with Rubric T
python3 -m berilo.eval.verify --what mt
```

Each arm gets its **own cache**. That is not tidiness: a shared cache would
serve the second arm from the first arm's rows at €0 and measure nothing, which
has happened in this project before. The same command compares the batching
change — `--what speed`.

On the sample corpus the dry run prints ~€0.11 for the LLM-only arm against
~€1.37 with Google. Run it before spending €13 on a book you care about. MT
spend is reported separately and never hides inside the token arithmetic.

## Reader app (Phase 2, Android / Boox)

An offline EPUB reader (Readium-based) with an LLM dictionary, paragraph
interpretation, highlights and notes. It reads whatever the translator CLI
produced — no cloud dependency.

Built for e-ink first: no animations, full-refresh page turns, pure black-on-white
theme, ≥ 48 dp touch targets, WCAG AA contrast.

**The app's source is not in this repository.** It moved to the private
`berilo-android` repository. This section describes what the app does, because
the translator's output is written to be read in it; it is not a build guide.

## How it was built

Berilo was built by a supervised multi-agent loop — one hypothesis at a time,
each measured against a rubric before it was allowed to stay. Over 100 commits,
32 recorded iterations, €6.68 of API spend, two days.

The full method — agent roles, the measurement loop, and the four things that
went wrong and what they taught — is written up here:

📄 **[How Berilo was built with agents](docs/how_it_was_built.md)** ·
🇸🇮 [v slovenščini](docs/how_it_was_built.sl.md)

<p align="center">
  <img src="assets/social/berilo-concrete-build-loop.png" alt="The Berilo build loop: 27 experiments, 5 translated books, €4.42 logged cost, 0 EPUB errors — hypothesis, build and review, measure and verify, defect to new hypothesis" width="620">
</p>

## Development

```bash
# Translator
cd translator
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
make test && make lint

# The Android app has its own repository and its own dev loop.
```

`make test` runs `python3 -m pytest`, so it uses whichever interpreter is on
your `PATH`. Activate the venv first, or the suite runs against a Python the
package was never installed into and fails at import — which looks like a broken
checkout and is not one.

## Principles

- Your books and your keys stay on your machine. Only text segments are sent to
  the LLM provider you configure.
- Translation quality is measured, not assumed — see the scoring harness in
  [`docs/rubric.md`](docs/rubric.md).
- Costs are visible before they are spent. No paid run starts without an estimate.
- Cheapest model that does the job is the default; every model is user-selectable.
- No piracy features. You supply files you own.

## License

MIT — Niko Gamulin, PhD. (Phases 1–2 are open source; the optional cloud sync
service is a separate closed-source product.)
