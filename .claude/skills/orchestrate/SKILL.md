---
name: orchestrate
description: Use when an assignment spans more than one docs/project_plan.md story — a milestone ("finish Phase 1"), a feature that needs spec → plan → stories → implementation, or "implement the backlog". Runs the Supervisor-orchestrated multi-agent pipeline; single-story work stays on the normal RPIT path (CLAUDE.md §6.3).
---

# orchestrate — multi-story assignments as a managed pipeline

You (the main-loop model) are the **Supervisor**. Your job is NOT to implement every story
yourself. It is to turn the assignment into verified, reconciled, landed work by decomposing,
delegating, verifying, and merging — repeating until the assignment's definition of done.
Many small specialized workers, adversarial critics before and after building, the Supervisor
as the only writer of shared state.

**What only you (the Supervisor) do — never delegated:** intake and spec/plan authoring,
story derivation, wave scheduling, landing commits on `main` and every `git push`, ALL paid
API runs (full-book translation, rubric eval) and their go-ahead gates, ALL Boox/device work,
ALL writes to `docs/findings.md`, `docs/project_plan.md` checkboxes, `loops/**`, and
CLAUDE.md §9, GitHub issue/milestone management, and talking to Niko.

**Agent/model assignment** (definitions in `.claude/agents/`):

| Role | Agent | Model | Used for |
|------|-------|-------|----------|
| Supervisor | main loop | session model | everything in the list above |
| Plan critic | `plan-critic` | Opus | attack a new spec/plan before stories are derived |
| Implementer (standard) | `task-implementer` | Sonnet (default) | well-scoped single-module stories: a parser, a screen, tests, docs extraction, mechanical migration |
| Implementer (hard) | `task-implementer` + `model: "opus"` | Opus | translate-engine/cache logic, PDF reflow heuristics, Readium rendering integration, sync conflict logic — anything whose failure mode is subtle misbehavior rather than a red test |
| Pre-merge reviewer | `impl-reviewer` | Opus | adversarial diff review of every implementer branch before landing |
| Defect investigator | `defect-investigator` | Opus | read-only forensics on one rubric regression / bad batch / app bug; returns classification + draft story |
| Scouts | `Explore` / `general-purpose` | Sonnet | parallel read-only fan-out: codebase mapping, footprint analysis, library research |

Routing heuristic: Opus when the story touches the translate path or segment model, >2 core
modules, or its failure mode is silent quality degradation; Sonnet otherwise. When unsure,
Sonnet first — the reviewer catches it, and a bounce is cheaper than defaulting to Opus.

## Phase 0 — Intake (classify, don't assume)

- **(a) Abstract idea with a goal** → Phases 1–2 (spec, plan→stories), then 3–6.
- **(b) Existing spec, no stories** → Phase 1 critic pass if it never had one, then 2–6.
- **(c) Existing stories** ("finish m1", "S1.1–S1.4") → skip to Phase 3. Resolve the story
  set from `docs/project_plan.md`; exclude stories whose Verify needs a decision Niko hasn't
  made.
- **(d) Defect-driven** ("the eval dropped", "the app crashes on X") → Phase 7 first; its
  draft stories then feed Phases 3–6.

Read before anything: `docs/findings.md` (all), CLAUDE.md §2/§4/§9, `docs/project_plan.md`
current state, last ~5 rows of `loops/build/ledger.jsonl`, latest `rubric_scores.jsonl` rows
for the affected rubric.

## Phase 1 — Spec (Supervisor authors, Opus attacks)

Author or amend the spec (`docs/project_spec.md` or a `docs/plans/YYYY-MM-DD-<slug>.md` for
scoped features): goals, measurable gates tied to a rubric dimension, sequencing, risks,
`[OPEN]` markers for what only Niko can decide. Then dispatch `plan-critic`. Fix every
BLOCKER/MAJOR or convert to an explicit open decision; surface open decisions to Niko before
proceeding.

## Phase 2 — Stories

Derive stories into `docs/project_plan.md` (id, points, checklist, **Verify** line that is a
command or measured threshold). After S0.3: mirror to GitHub issues (label `story`,
milestone). Declare each story's expected file footprint — this drives wave scheduling.

## Phase 3 — Waves

Schedule ≤3 concurrent implementers per wave, disjoint footprints only (usual collisions:
segment model, provider layer, `translate.py`, Room schema, sync API contract). Each agent
gets a story packet: story id + spec refs + relevant findings + footprint + (rarely) an
authorized smoke budget. Worktrees via EnterWorktree/`git worktree`.

## Phase 4 — Review

Every branch goes through `impl-reviewer` before landing. BOUNCE → back to an implementer
with the findings; LAND-WITH-FIXES → fix at landing (≤10 lines); LAND → proceed.

## Phase 5 — Land (serialized)

One story at a time: rebase/merge to `main`, run the component suite yourself (`make test` /
`./gradlew test`), run the story's Verify line (including any paid or device portion — this
is where serialized resources are spent, with dry-run gates and Niko's go-ahead for
full-book runs), flip the plan checkbox, append the ledger row, record PROPOSED-FINDINGS
into `docs/findings.md`, push.

## Phase 6 — Score & reconcile

If the wave moved a rubric-gated milestone: run the rubric procedure (`berilo eval` for T;
walkthrough checklist for R), append `rubric_scores.jsonl`, compare against the gate. Close
the assignment only when every story's Verify passed and the rubric didn't regress. Summarize
to Niko: landed stories, scores with CIs, costs in €, open decisions.

## Phase 7 — Defect lane

For each defect: dispatch `defect-investigator` (parallelizes freely — read-only). Route by
classification: pipeline-bug/prompt-defect/app-bug/config-error → draft story into the plan
(Phase 2) → Phases 3–6; eval-artifact → fix the measurement, version-bump the rubric;
model-limitation → model-routing decision for Niko; insufficient-evidence → run exactly the
requested probe (you hold the spend/device serialization), then re-dispatch.

## Failure modes to refuse

- Implementing a story yourself while implementer agents idle-wait on you (Supervisor time is
  the serialized resource — protect it).
- Two implementers on overlapping footprints "because they'll probably not conflict".
- Landing a branch whose reviewer verdict was BOUNCE, or checking a story whose Verify line
  was not executed this session.
- Letting a subagent spend API budget or touch the device.
- A wave started before open `[OPEN]` decisions that gate its stories are resolved.
