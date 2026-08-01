# CLAUDE.md — Berilo

> Context for Claude Code in this repository. **Keep this file updated** after
> major changes, milestones, or new constraints. Details live in `docs/` —
> link, don't duplicate.

---

## 1. Project Goals

Berilo lets readers translate books (PDF/EPUB/MOBI) into their own language
with meaning-preserving LLM translation, and read them in a purpose-built
Android app with an LLM dictionary, paragraph interpretation, and notes.
First user: Niko, English → Slovenian, Boox e-ink tablet.

**Three phases, shipped gradually — each verified before the next starts:**

1. **Translator CLI** (`translator/`, Python) — file in, translated EPUB out. Open source. **This repo.**
2. **Reader apps** — Android (separate **private** repo `berilo-android`, Kotlin/Compose/Readium) and iOS (separate **private** repo `berilo-ios`, Swift). Offline readers with LLM features. Closed source.
3. **Cloud service** (separate **private** repo `berilo-cloud`) — Vercel/Next.js + Supabase sync and web note review. Closed source; only the API contract lives here.

Full spec: [`docs/project_spec.md`](docs/project_spec.md).

## 2. Architecture Overview

```
translator/ (Python CLI)        berilo-android (private)      berilo-cloud (private)
 pdf|epub|mobi → normalize →     Readium reader + Room  ⇄     Next.js/Vercel + Supabase
 translate → verify → EPUB       LLM dictionary/notes          notes/highlights sync
        │                       berilo-ios (private)
        │                        Swift port of the same
        └── user's own LLM API key; cheap models by default; every model user-overridable

This repo is the **reference implementation** of the translation core. The
apps are ports of it; `berilo-cloud` imports it as a package. See the
`berilo-project` workspace repo for the full map.
```

**Key architectural rules:**
- **BYO API key.** Keys come from `.env` (CLI) or encrypted device storage (app). Never in code, logs, or git.
- **Cheapest model that does the job** is the default (`gpt-5-mini`); users can select any OpenAI/Anthropic model. One provider interface, no provider lock-in.
- **Meaning preservation over literalism** — the translation prompt-and-verify design is the product.
- **Segment integrity:** source↔target mapping is 1:1; nothing is silently dropped; failures are loud and resumable.
- **Books never leave the device/machine** except as segment batches to the LLM API. Phase 3 syncs only user-created data.
- **EPUB is the canonical interchange format** (translator output = app input).

## 3. Design & UX

See [`docs/design_guidelines.md`](docs/design_guidelines.md). The text is the
hero; chrome recedes. E-ink first (Boox), Literata + Inter, one accent color,
no engagement mechanics, WCAG AA.

## 4. Constraints and Policies

- **No piracy features** — user supplies files they own; no downloading/sharing/DRM stripping.
- **Secrets:** `.env` is gitignored; before every commit run the secret scan (§7). API keys in the app live in EncryptedSharedPreferences only.
- **`data/` is gitignored** — copyrighted books; never commit or upload.
- **Costs are visible:** every translation run reports estimated (dry-run) and actual cost. Never burn API budget silently; full-book runs need explicit go-ahead.
- **Python:** 3.10+, type hints, Google docstrings, Black, logging not print, named constants. **Kotlin:** Compose, no blocking on main thread. **TS (Phase 3):** functional components, named exports, no bare `any`.
- **Numbers with uncertainty:** sampled metrics report bootstrap CIs (rubric scoring, cost estimates).
- **Open/closed boundary:** nothing service-private in this repo; nothing from `berilo-cloud` copied here except the API contract.
- **Supabase (Phase 3):** always paginate (1000-row default); RLS on every table; test time logic across DST.

## 5. Repo / Git Etiquette

- Remote: `git@github.com:nikogamulin/berilo.git`, default branch `main`. Feature branches → `main` via PR.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:` — imperative mood, reference issue numbers.
- Small, focused PRs. Never force-push shared branches. MIT license, author: Niko Gamulin, PhD.

## 6. Build Loop & Agentic Workflow

### 6.1 Knowledge tiers (read before working, promote as you learn)

| Tier | Where | Cost |
|------|-------|------|
| **Tier 1** — canonical rules | this file, §9 | ~0, read once per session |
| **Tier 2** — findings register | [`docs/findings.md`](docs/findings.md) | cheap — scan before acting |
| **Tier 3** — live research/debugging | in-session | expensive — record results into Tier 2 |

Every iteration: read `docs/findings.md` first → work → record new findings →
promote recurring/endorsed findings to §9. The loop's purpose is to make
rediscovery unnecessary.

### 6.2 The outer loop — rubric-driven

The process optimizes the rubrics in [`docs/rubric.md`](docs/rubric.md)
(T = translation quality, R = reader experience, S = sync/cloud,
D = process health). Per iteration: **one hypothesis → implement (RPIT) →
score the affected rubric via its defined procedure → keep** (score not
regressed, tests green) **or discard** (`git reset --hard HEAD~1`) → append a
row to [`loops/build/ledger.jsonl`](loops/build/ledger.jsonl)
(`{"date","hypothesis","result","kept","rubric_delta","cost_eur"}`) → record
findings. Rubric scores go to `loops/build/rubric_scores.jsonl`.

### 6.3 RPIT inner cycle — Research → Plan → Implement → Test

- **Research** (optional): only when unknowns block confident planning; findings land in the plan issue or `docs/findings.md`.
- **Plan:** [`docs/project_plan.md`](docs/project_plan.md) is the task list. Every task has a **Verify** line — an executable command or measured threshold. **A task may be checked off only in the session where its Verify line was run and passed.** After S0.3, stories are mirrored to GitHub issues (label `story`, milestone `m0`–`m3`, checkbox bodies); the issue is then the progress source of truth.
- **Implement:** feature branch + PR linked `Refs #<issue>`; update checkboxes as you go.
- **Test:** `make test` in the touched component + the story's Verify line. Pass → merge, close. Fail → bug issue (label `bug`, `Refs #<story>`), enter the debug loop: comment each attempt (tried/result/next hypothesis) until fixed.

**Debugging protocol:** fresh build first; if root cause not found after 10
tool calls, stop and list 3–5 alternative hypotheses; after a fix, test actual
user-facing behavior and check regressions before committing.

### 6.4 Multi-agent execution

Single-story work runs RPIT directly in the main loop. **Assignments spanning
more than one story** run supervisor-orchestrated:

Pipeline and agent definitions live in [`.claude/`](.claude/) — invoke the
**`/orchestrate` skill** (`.claude/skills/orchestrate/SKILL.md`) for multi-story
work; use **`/verify-implementation`** before claiming any story done.

| Role | Agent (`.claude/agents/`) | Model | Lane |
|------|------|-------|------|
| **Supervisor** (main loop) | — | session model | plan authoring, issue creation, wave scheduling, PR merge, all shared-state writes (`docs/findings.md`, `loops/**`, plan checkboxes, §9), all serialized resources (Boox device installs, full-book paid translation runs, GitHub pushes, Vercel/Supabase), talking to Niko |
| Plan critic | `plan-critic` | opus | attacks a spec/plan before issues: unverifiable Verify lines, contradictions with §9/findings, footprint collisions, cost realism |
| Task implementer | `task-implementer` | sonnet (opus for pipeline-core/reader-rendering/sync stories) | ONE story per agent, isolated worktree, to the bar: tests written + green (LLM calls mocked), offline part of the Verify line satisfied; structured report with `PROPOSED-FINDINGS` |
| Impl reviewer | `impl-reviewer` | opus | adversarial pre-merge diff review: scope, §9 compliance, test honesty, secret/cost-safety scan |
| Defect investigator | `defect-investigator` | opus | read-only forensics on ONE defect (rubric regression, bad translation batch, app bug): classifies and returns a routing verdict + draft task |

**Invariants:** shared state is single-writer (Supervisor only); parallel
implementers only on disjoint file footprints, ≤3 concurrent; paid API runs
and device testing never delegated to subagents; confirmation gates bind the
Supervisor too. A plan or diff contradicting a recorded finding bounces.

## 7. Frequently Used Commands

```bash
# Environment
cp .env.example .env                       # then fill keys (already done locally)

# Phase 1 (once S0.2 lands)
cd translator && pip install -e ".[dev]"
make test && make lint
berilo doctor                            # provider smoke test (1 sentence, ~€0)
berilo inspect data/examples/<file>      # extraction preview, no API cost
berilo translate <file> --to sl --dry-run  # cost estimate — ALWAYS before a full run
berilo eval <translated.epub> --sample 40 --seed 42   # Rubric T score + CI

# Phase 2 — the Android app lives in the private berilo-android repo now.
# cd ../berilo-android && ./gradlew assembleDebug test

# Secret scan — run before EVERY commit (exclusions = docs that describe the scan itself)
git grep --cached -iE 'sk-(proj|ant)|api03|/home/niko' -- ':!CLAUDE.md' ':!docs/rubric.md' ':!.claude/' && echo LEAK || echo clean
```

## 8. References

| Document | Path | Contents |
|----------|------|----------|
| Product spec | [`docs/project_spec.md`](docs/project_spec.md) | Problem, users, all 3 phases, pipeline design, stack decisions, non-goals |
| Project plan | [`docs/project_plan.md`](docs/project_plan.md) | Task list — phases m0–m3, stories with points and **Verify** lines |
| Rubrics | [`docs/rubric.md`](docs/rubric.md) | T/R/S scoring procedures + weights + gates; process-health checklist D |
| Design guidelines | [`docs/design_guidelines.md`](docs/design_guidelines.md) | Principles, typography, e-ink rules, components, anti-patterns |
| Findings (Tier 2) | [`docs/findings.md`](docs/findings.md) | Session-discovered gotchas and working commands — scan first |
| Ledger | [`loops/build/ledger.jsonl`](loops/build/ledger.jsonl) | One row per kept/discarded iteration |
| Rubric scores | [`loops/build/rubric_scores.jsonl`](loops/build/rubric_scores.jsonl) | Score history with commit + dimensions |
| Orchestration | [`.claude/skills/orchestrate/SKILL.md`](.claude/skills/orchestrate/SKILL.md) | Supervisor pipeline (intake → spec+critic → waves → review → land → score); agents in [`.claude/agents/`](.claude/agents/); verification bar in [`.claude/skills/verify-implementation/SKILL.md`](.claude/skills/verify-implementation/SKILL.md); session reflection hook in [`.claude/hooks/`](.claude/hooks/) |

## 9. Learned Rules — Tier 1 canonical (add as mistakes happen)

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
