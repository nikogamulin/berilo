# Berilo — Rubric T

> The development process optimizes these scores. Every score is produced by a
> **defined, repeatable procedure** — never by feel. Scores are appended to
> `loops/build/rubric_scores.jsonl` as
> `{"date", "rubric", "version", "score", "dimensions": {...}, "commit", "notes"}`.
> A change that lowers a rubric score without an explicit tradeoff decision is
> discarded.

This file once held all four rubrics, from when Berilo was one repository. Each
is now kept by whoever can actually run it: **T** here, **R** in
`berilo-android`, **S** in `berilo-cloud`, **D** in the workspace repo. The
three that left leave a pointer below rather than a copy — a rubric stated twice
is a rubric that will disagree with itself.

---

## Rubric T — Translation Quality (Phase 1) — 0–100

Scoring procedure: `python -m berilo.eval <book> --sample 40 --seed 42` runs
the full procedure below and prints the weighted score with a bootstrap 95% CI.
Judge model defaults to `BERILO_JUDGE_MODEL`; judge prompts live in
`translator/berilo/eval/prompts/` and are versioned — score rows record the
prompt version.

**v1.1 (2026-07-24):** T2/T3 pair sampling and the T6 screen pool draw from
body-prose PARAGRAPH segments only (front/back-matter chapters excluded;
fallback to the full pool is logged and noted in the score row). T4 counts
term occurrences on word boundaries, not substrings ("UN" no longer matches
inside "united"). Rationale: defect investigation showed v1.0 pooled Notes
citation fragments as prose and inflated T4 denominators — measurement
artifacts, not translation quality (evidence in `loops/build/ledger.jsonl`
and `docs/findings.md`).

| Dim | What | Weight | Measurement |
|-----|------|--------|-------------|
| T1 | **Completeness** | 20 | % of source segments with non-empty translation, 1:1 mapping. Computed exactly (no sampling). <100% caps the total rubric score at 40 — a book with holes is not a book. |
| T2 | **Meaning preservation** | 30 | LLM judge scores 40 random segment pairs (seed 42) 1–5 on "does the Slovenian convey the full meaning of the English, nothing added, nothing lost". Score = mean/5 × 30. Report bootstrap CI. |
| T3 | **Fluency / naturalness** | 20 | Same 40 samples, judged 1–5 on "reads as native Slovenian written by a professional translator; idioms rendered natively; correct šumniki". Mean/5 × 20. |
| T4 | **Terminology consistency** | 10 | For the 20 most frequent glossary terms: % of occurrences translated with the glossary rendering (exact string check, lemmatization-tolerant). |
| T5 | **Structural fidelity** | 10 | Automated diff: chapter count, heading levels, list/blockquote preservation, italics/bold retention rate between source and output EPUB. |
| T6 | **Extraction cleanliness** (PDF inputs) | 5 | Judge screens 20 random output paragraphs for artifacts (inline page numbers, running headers, broken hyphenation). % clean. EPUB inputs: automatic 5. |
| T7 | **Cost efficiency** | 5 | Full 5 if actual cost ≤ 1.5× dry-run estimate and ≤ €1.50 per 100k words at default models; linear to 0 at 3×. |

**Gates:** T1 = 100% is a release gate for any book. Target: **T ≥ 85** on all
three example books before Phase 1 is declared done.

---

## Rubric R — Reader Experience — scored elsewhere

> **Moved to `berilo-android/docs/rubric.md`.** R scores the Android reader,
> which is no longer in this repository, and a rubric scored against something
> you cannot run is a rubric that goes stale silently.
>
> **S** (Sync & Cloud) is scored in `berilo-cloud`; **D** (process health) is
> the workspace repo's, at `../docs/rubrics.md`. **T**, below, is this repo's
> and stays.

## Rubric S — Sync & Cloud — scored elsewhere

> **Moved to `berilo-cloud/docs/rubric.md`.** S scores the sync service and the
> web layer, neither of which is in this repository.

## Rubric D — Development Process Health — scored elsewhere

> **Moved to the workspace repo, `../docs/rubrics.md`.** D judges how work is
> done across every repository, not what any one of them produces, so it
> belongs to the map rather than to a member. It also gained two conditions
> that only exist in a multi-repo workspace.
