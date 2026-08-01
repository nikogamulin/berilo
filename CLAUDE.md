# CLAUDE.md — berilo

> Context for Claude Code in this repository. **Keep this file updated** after
> major changes, milestones, or new constraints. Details live in `docs/` and
> `contracts/` — link, don't duplicate.

**This repository is the Python translator and the contracts.** It is public and
MIT. The reader apps and the cloud service are separate, private repositories.

---

## 0. Before you write anything: is this the right repository?

Berilo is five repositories. Work lands in the **wrong** one easily, because
they share vocabulary — every one of them has a "reader", a "translate", a
"sync".

| If the request is about… | It belongs in | Here? |
|---|---|---|
| the Python translator, the `berilo` CLI, prompts, the eval harness | **here** | yes |
| a contract — sync wire format, the core spec, design guidelines | **here** (`contracts/`) | yes |
| the Android reader app, Kotlin, Compose, Readium, the Boox | `../berilo-android/` | no |
| the iOS app, Swift, BeriloKit, Xcode, the App Store | `../berilo-ios/` | no |
| the web app, berilo.app, sync service, Supabase, Clerk, cloud translation | `../berilo-cloud/` | no |
| a cross-repo runbook, the workspace manifest, `bin/berilo` | `../` (the workspace repo) | no |

If the answer is "no", **stop and say so** rather than writing something
approximate here. There is no Kotlin, Swift, or TypeScript in this repository,
and a change made here for an app that lives elsewhere is a change nobody will
find.

### The path trap

The four app repos are cloned as **siblings inside the workspace checkout**, and
that checkout is *also* called `berilo`:

```
workspace/berilo/          <- the workspace repo (named berilo-project on GitHub)
├── berilo/                <- THIS repo
├── berilo-android/  berilo-ios/  berilo-cloud/
└── docs/ bin/ repos.toml
```

So from here the workspace repo is `..` and its runbooks are `../docs/runbooks/`
— never `../berilo-project/docs/`, which does not exist. Check a cross-repo path
resolves before committing it; `[ -e path ] && echo ok` costs nothing.

## 1. What this repo is

Berilo translates books (PDF/EPUB/MOBI) into a reader's own language with
meaning-preserving LLM translation. **This repository is the translation
engine** — the CLI that does it, and the contracts that everything else
implements. First user: Niko, English → Slovenian.

The honest framing, because the README has to defend it: *the engine is open and
independently verifiable*, not *Berilo is open source*. Anyone can run the
translator on their own books with their own API key, read exactly what it sends
to a provider, and check the output. The reader apps and the cloud service are
separate private products.

| Milestone | What | Where it is tracked |
|---|---|---|
| `m1` | Translator CLI — normalize, translate, assemble, eval | here, complete |
| `m2`, `m4` | Android reader and on-device translation | `berilo-android` |
| `m3` | Cloud sync and web review | `berilo-cloud` |
| `m5` | iOS app | `berilo-ios` |

Full spec: [`docs/project_spec.md`](docs/project_spec.md). Cross-repo index:
`../docs/roadmap.md`.

## 2. Architecture

```
translator/ (Python CLI)          berilo-android (private)     berilo-cloud (private)
 pdf|epub|mobi → normalize →       Kotlin port of the core  ⇄   imports this package
 translate → verify → EPUB         Readium reader + Room        Next.js/Vercel + Supabase
        │                         berilo-ios (private)
        │                          Swift port of the core
        └── the user's own LLM API key; cheap models by default; all overridable
```

This repo is the **reference implementation** of the translation core. Kotlin
and Swift are hand-written ports held to it by conformance vectors;
`berilo-cloud` imports it as a package and so is conformant by construction.
See [`contracts/core-spec.md`](contracts/core-spec.md).

**Architectural rules:**
- **BYO API key.** Keys come from `.env`. Never in code, logs, or git.
- **Cheapest model that does the job** is the default (`gpt-5-mini`); any
  OpenAI/Anthropic model is selectable. One provider interface, no lock-in.
- **Meaning preservation over literalism** — the prompt-and-verify design is the
  product.
- **A quality claim needs a score.** "This should translate better" is a
  hypothesis; `corpus/` makes measuring it cost cents, so measure it. Quote the
  tier and the language, and never quote a `smoke` score as a quality result.
- **Segment integrity:** source↔target mapping is 1:1; nothing is silently
  dropped; failures are loud and resumable.
- **Books never leave the machine** except as segment batches to the LLM API.
- **EPUB is the canonical interchange format** — translator output is app input.

## 3. Contracts: this repo owns them, so they change here

`contracts/` holds what binds the other repositories:
[`sync_api.md`](contracts/sync_api.md),
[`core-spec.md`](contracts/core-spec.md),
[`design_guidelines.md`](contracts/design_guidelines.md), and
[`conformance.md`](contracts/conformance.md) with the generated
`contracts/vectors/`.

Three rules, and the third is the one that gets broken:

1. A contract changes **here first**, then in every implementer the workspace
   manifest lists under `contracts_implemented`.
2. An implementer that disagrees with a contract has a bug **in the
   implementer** — until the contract changes here.
3. **Vectors are generated, never edited.** If a vector and a port disagree, the
   port is wrong. If a vector and this Python disagree, regenerate the vector.
   Hand-editing one to make a suite green destroys the only evidence that the
   platforms still agree.

See [`contracts/README.md`](contracts/README.md).

## 4. Constraints and policies

- **No piracy features** — the user supplies files they own; no downloading,
  sharing, or DRM stripping.
- **Open/closed boundary.** This repo is world-readable and permanent. Nothing
  service-private — API keys, Supabase project refs, Clerk secrets, infra
  topology, cost or revenue figures — may enter it, in code, docs, tests, or
  commit messages. Only a contract change flows back here from a private repo,
  and only as a contract change.
- **Secrets:** `.env` is gitignored; run the secret scan (§7) before every
  commit.
- **`data/` is gitignored** — copyrighted books; never commit or upload. This is
  also why `contracts/vectors/` may hold only derived values.
- **Costs are visible:** every run reports estimated (dry-run) and actual cost.
  Never burn API budget silently; full-book runs need explicit go-ahead.
- **Python:** 3.10+, type hints, Google docstrings, Black, logging not print,
  named constants.
- **Numbers with uncertainty:** sampled metrics report bootstrap CIs.

## 5. Repo / git etiquette

- Remote: `git@github.com:nikogamulin/berilo.git`, default branch `main`.
  Feature branches → `main` via PR.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`,
  `chore:`, `docs:`, `refactor:`, `test:` — imperative mood, reference issue
  numbers.
- Small, focused PRs. Never force-push shared branches. MIT, author: Niko
  Gamulin, PhD.
- The Android app is still in this repo's **history** under MIT, and stays
  there. The split made future Android work private; it did not retract what was
  already published.

## 6. Build loop and agentic workflow

### 6.1 Knowledge tiers (read before working, promote as you learn)

| Tier | Where | Cost |
|------|-------|------|
| **Tier 1** — canonical rules | this file, §9 | ~0, read once per session |
| **Tier 2** — findings register | [`docs/findings.md`](docs/findings.md) | cheap — scan before acting |
| **Tier 3** — live research/debugging | in-session | expensive — record results into Tier 2 |

Every iteration: read `docs/findings.md` first → work → record new findings →
promote recurring ones to §9. The loop exists to make rediscovery unnecessary.

### 6.2 The outer loop — rubric-driven

This repo is scored on **Rubric T** (translation quality),
[`docs/rubric.md`](docs/rubric.md). R, S and D moved to the repos that can
actually run them — `berilo-android`, `berilo-cloud`, and the workspace repo.

Per iteration: **one hypothesis → implement (RPIT) → score Rubric T by its
defined procedure → keep** (score not regressed, tests green) **or discard**
(`git reset --hard HEAD~1`) → append a row to
[`loops/build/ledger.jsonl`](loops/build/ledger.jsonl) → record findings. Scores
go to `loops/build/rubric_scores.jsonl`.

### 6.3 RPIT inner cycle — Research → Plan → Implement → Test

- **Research** (optional): only when unknowns block confident planning; findings
  land in the plan issue or `docs/findings.md`.
- **Plan:** [`docs/project_plan.md`](docs/project_plan.md) is the task list.
  Every task has a **Verify** line — an executable command or measured
  threshold. **A task may be checked off only in the session where its Verify
  line was run and passed.**
- **Implement:** feature branch + PR linked `Refs #<issue>`.
- **Test:** `make test` in `translator/` plus the story's Verify line.

**Debugging protocol:** fresh build first; if the root cause is not found after
10 tool calls, stop and list 3–5 alternative hypotheses; after a fix, test
actual user-facing behaviour and check regressions before committing.

### 6.4 Multi-agent execution

Single-story work runs RPIT directly. **Assignments spanning more than one
story** run supervisor-orchestrated: invoke the **`/orchestrate` skill**
(`.claude/skills/orchestrate/SKILL.md`); use **`/verify-implementation`** before
claiming any story done.

| Role | Agent (`.claude/agents/`) | Model | Lane |
|------|------|-------|------|
| **Supervisor** (main loop) | — | session model | plan authoring, issue creation, wave scheduling, PR merge, all shared-state writes (`docs/findings.md`, `loops/**`, plan checkboxes, §9), all serialized resources (paid full-book runs, GitHub pushes), talking to Niko |
| Plan critic | `plan-critic` | opus | attacks a spec/plan before issues: unverifiable Verify lines, contradictions with §9/findings, footprint collisions, cost realism |
| Task implementer | `task-implementer` | sonnet (opus for pipeline-core stories) | ONE story per agent, isolated worktree, tests written and green (LLM calls mocked); structured report with `PROPOSED-FINDINGS` |
| Impl reviewer | `impl-reviewer` | opus | adversarial pre-merge diff review: scope, §9 compliance, test honesty, secret/cost-safety scan |
| Defect investigator | `defect-investigator` | opus | read-only forensics on ONE defect; returns a routing verdict + draft task |

**Invariants:** shared state is single-writer (Supervisor only); parallel
implementers only on disjoint file footprints, ≤3 concurrent; paid API runs are
never delegated to subagents; confirmation gates bind the Supervisor too. A plan
or diff contradicting a recorded finding bounces.

## 7. Frequently used commands

```bash
cp .env.example .env                       # then fill keys

cd translator && pip install -e ".[dev]"
make test && make lint
berilo doctor                              # provider smoke test (1 sentence, ~€0)
berilo inspect data/examples/<file>        # extraction preview, no API cost
berilo translate <file> --to sl --dry-run  # cost estimate — ALWAYS before a full run
berilo serve                               # hand a translated book to a tablet over the LAN
berilo eval <translated.epub> --sample 40 --seed 42   # Rubric T score + CI

# The evaluation corpus — score a change without translating a whole book.
# smoke: 40 paras, ~EUR0.036/lang, ~1 min.  standard: 150 paras, ~EUR0.125/lang.
PYTHONPATH=translator python3 -m berilo.eval.corpus build    # rebuild the samples
PYTHONPATH=translator python3 -m berilo.eval.corpus verify   # after ANY normalize change
berilo translate corpus/build/berilo-sample-smoke.epub --to sl -y
berilo eval corpus/build/berilo-sample-smoke.sl.epub

# Regenerate the conformance vectors after any change to the core (from the repo root)
PYTHONPATH=translator python3 -m berilo.identity_fixture
PYTHONPATH=translator python3 contracts/gen/generate_assemble_vectors.py

# Secret scan — run before EVERY commit (exclusions = docs that describe the scan)
git grep --cached -iE 'sk-(proj|ant)|api03|/home/niko' -- ':!CLAUDE.md' ':!.claude/' && echo LEAK || echo clean
```

## 8. References

| Document | Path | Contents |
|----------|------|----------|
| Contracts | [`contracts/README.md`](contracts/README.md) | what binds the other repos, and the rule for changing it |
| Core spec | [`contracts/core-spec.md`](contracts/core-spec.md) | the seven surfaces the ports must agree on |
| Conformance | [`contracts/conformance.md`](contracts/conformance.md) | what a port must assert; vector release and vendoring; the current gaps |
| Sync contract | [`contracts/sync_api.md`](contracts/sync_api.md) | wire format and SQL schema |
| Design guidelines | [`contracts/design_guidelines.md`](contracts/design_guidelines.md) | principles, typography, e-ink rules, components, anti-patterns |
| Product spec | [`docs/project_spec.md`](docs/project_spec.md) | problem, users, pipeline design, stack decisions, non-goals |
| Project plan | [`docs/project_plan.md`](docs/project_plan.md) | this repo's stories with points and **Verify** lines |
| Rubric T | [`docs/rubric.md`](docs/rubric.md) | translation-quality scoring procedure, weights, gates |
| Evaluation corpus | [`corpus/README.md`](corpus/README.md) | the fixed sample books: which tier to run when, what they cost, and what they do not cover |
| Findings (Tier 2) | [`docs/findings.md`](docs/findings.md) | session-discovered gotchas and working commands — scan first |
| Ledger | [`loops/build/ledger.jsonl`](loops/build/ledger.jsonl) | one row per kept/discarded iteration |
| Workspace map | `../README.md`, `../docs/` | the five repos, runbooks, contracts index |

## 9. Learned rules — Tier 1 canonical (add as mistakes happen)

<!-- When a mistake happens, don't just fix it — add the rule.
     Format: what went wrong → what the rule is now.
     Promotion target for recurring/endorsed Tier-2 findings. -->

- Example brief said "pdf or mobi" but the examples are PDF+EPUB → **verify
  input assumptions against `data/examples/` before building format support;
  EPUB is first-class, MOBI goes through `ebook-convert`.**
- Agent worktrees resolve installed packages and `data/` to the main
  checkout (recurred 3×) → **run worktree tests with
  `PYTHONPATH=<worktree>/translator`; keep `data/`-gated tests skippable;
  never copy books into worktrees** (details in `docs/findings.md`).
- A cache key that omits an experimental factor silently turns every
  experiment on that factor into a no-op that looks like a null result (the
  translation cache keyed `(book, segment, model, lang)` but not the prompt,
  so a prompt change would have re-served old text at €0) →
  **before trusting any null result, check that the cache key contains the
  thing you changed.** Corollary: `book_hash`/`segment_hash` cover segment IDs
  and text only, so normalize fixes that change types or titles rebuild from
  cache for €0 — verify the hash before assuming a paid re-run.
- Measure the judge before tuning the thing it judges: intra-sample σ plus the
  verdict distribution costs ~€0.15 and settles "is this a real ceiling or a
  measurement artifact". **A judge that already awards top marks to some of
  your output is not what is capping you.** Related: fixing a measurement
  artifact can move a score DOWN (untranslated headings scored meaning 5/5),
  so judge such fixes by pool cleanliness, not score direction.
- Screen-gate fixes chased flagged instances for 4 rounds while the
  fixed-seed sample kept redrawing (recurred 3×) → **fix quality gates by
  artifact CLASS (type/fold/exclude whole categories), never by instance;
  any pool change redraws a seeded sample, so per-instance fixes are
  non-monotonic** (evidence in `docs/findings.md`).
- A generator that writes into another repository's tree breaks the moment that
  tree moves, and breaks *silently* in the direction that matters — the vectors
  simply stop being regenerated. `identity_fixture.py` wrote into
  `android/app/src/test/`, and `generate_assemble_vectors.py` sat in the Android
  repo while importing `berilo.assemble`, so after the split neither could run
  anywhere → **a generator lives in the same repository as the code it executes
  and writes to `contracts/vectors/`; ports vendor a copy.**
- The secret scan was once made green by appending an exclusion instead of
  fixing the content, after which every commit reported clean while a real home
  path sat in the repo → **when the scan is red, fix the content.** An
  exclusion is only ever for a file that documents the scan itself.
