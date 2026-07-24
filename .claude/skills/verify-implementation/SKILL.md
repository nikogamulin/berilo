---
name: verify-implementation
description: Use when a Berilo feature or bugfix was just implemented and you need to confirm it actually works before claiming it's done — verifies via deterministic scenarios (mocked LLM, no cost) then gated live scenarios (capped paid smoke / Boox device), fixing to green.
---

# verify-implementation

## Overview

Verify a Berilo change with **scenario-based testing driven to green**, in two gated stages:
first **deterministic** scenarios (fast, free, rerunnable-identical), then
**gated live** scenarios (paid API smoke and/or Boox device — final confirmation). The bar is
not "tests exist" — it is **every scenario meets its pre-defined criterion**, with the
fix→rerun loop run to completion.

**Core principle:** unit-green is not "done." A scenario you ran once and stopped at "root
cause identified" is not done. You either drive the complete set to green, or — if a bar
genuinely can't be met — you **state plainly why** with evidence. No silent deferral dressed
up as success.

## The process

### Stage 0 — Define the bar BEFORE testing

- List the **major capabilities** the change touches (e.g. for a translate-engine change:
  segment integrity, cache resume, glossary injection, context continuity, cost accounting).
  Pick **N scenarios that cover every one** — not just the line you edited.
- Write the success criterion per scenario up front: exact assertions for deterministic;
  the rubric dimension and threshold (docs/rubric.md) for live. Criteria are decided
  *before* you run, not fitted to results.
- Write down the live stage's **cost cap in €** and get Niko's go-ahead if it exceeds a
  trivial smoke (~€0.20).

### Stage 1 — Deterministic scenarios → all green (the quality gate)

1. Add the N scenarios as tests (translator: `translator/tests/`, pytest; app:
   `android/app/src/test/`, JUnit/robolectric). One test per capability, each docstring
   stating its criterion. Drive the **real** production code; double only the
   non-deterministic seam (the LLM client — a deterministic fake that returns tagged
   pseudo-translations, so segment mapping and cache behavior are fully checkable).
2. **Give each scenario teeth:** breaking the capability must fail it. Verify by mutating
   the code once (revert after) or asserting on the precise observable, not on "no exception".
3. Run the full component suite, not just the new tests. Fix → rerun to green. Record the
   final counts.

### Stage 2 — Gated live scenarios (serialized, Supervisor-run)

1. **Paid smoke** (translator changes): a bounded run — e.g. 1 chapter or ≤50 segments at
   the default model — through the real provider. Criteria: completeness 100%, spot-check
   k segments against their criterion, actual cost ≤ cap. For rubric-gated milestones, the
   full `berilo eval --sample 40 --seed 42` with CI.
2. **Device walkthrough** (app changes): install the APK on the Boox, run the affected
   subset of `docs/checklists/reader_walkthrough.md`, measure the R-dimension thresholds via
   the debug overlay — never eyeball latencies.
3. Any failure here goes back through Stage 1: reproduce deterministically first (add the
   missing scenario), fix, re-run both stages.

### Reporting

State per scenario: criterion → result → evidence (command output, cost, score ± CI). Then
the verdict: done / not done and why. Update `loops/build/ledger.jsonl`; propose findings for
anything non-obvious learned. Only after this may the story's checkbox flip.
