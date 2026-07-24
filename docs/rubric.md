# Bookworm — Rubrics

> The development process optimizes these scores. Every score is produced by a
> **defined, repeatable procedure** — never by feel. Scores are appended to
> `loops/build/rubric_scores.jsonl` as
> `{"date", "rubric", "version", "score", "dimensions": {...}, "commit", "notes"}`.
> A change that lowers a rubric score without an explicit tradeoff decision is
> discarded.

---

## Rubric T — Translation Quality (Phase 1) — 0–100

Scoring procedure: `python -m bookworm.eval <book> --sample 40 --seed 42` runs
the full procedure below and prints the weighted score with a bootstrap 95% CI.
Judge model defaults to `BOOKWORM_JUDGE_MODEL`; judge prompts live in
`translator/bookworm/eval/prompts/` and are versioned — score rows record the
prompt version.

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

## Rubric R — Reader Experience (Phase 2) — 0–100

Scoring procedure: manual timed walkthrough on the Boox device using the
checklist script `docs/checklists/reader_walkthrough.md` (created with the
Phase 2 test story); latencies measured with the app's built-in debug timing
overlay (logcat markers), not a stopwatch.

| Dim | What | Weight | Measurement |
|-----|------|--------|-------------|
| R1 | **Core reading reliability** | 25 | 3 example books: import, open, page through 3 full chapters, close, reopen at exact position. Any crash/misrender = per-incident −5. |
| R2 | **E-ink performance** | 15 | Page turn ≤ 150 ms render call on Boox (measured via debug overlay); no ghosting-inducing animation in e-ink mode; cold start to last page ≤ 3 s. Each of 3 criteria ≈ 5 pts. |
| R3 | **Dictionary UX** | 15 | Tap-to-definition ≤ 4 s online (p50 over 10 lookups); cached lookup ≤ 300 ms; context disambiguation verified on 5 ambiguous words; graceful offline message. |
| R4 | **Interpretation UX** | 10 | Long-press → interpretation ≤ 8 s p50; result cached; renders long answers readably. |
| R5 | **Notes & highlights** | 15 | Create/edit/delete highlight and note; survive app restart; notebook lists all; Markdown export opens correctly. 10 scripted actions, each 1.5 pts. |
| R6 | **Design quality** | 15 | 12-item visual checklist (typography scale, spacing rhythm, contrast on e-ink and OLED, empty states, dark mode) — each pass/fail. |
| R7 | **Key & settings safety** | 5 | API key never appears in logs/backups (audit `adb logcat` + backup extract); model switch takes effect without restart. |

**Gates:** R1 ≥ 20 and R7 = 5 are release gates. Target: **R ≥ 85** before
Phase 2 is declared done.

---

## Rubric S — Sync & Cloud (Phase 3) — 0–100

Scoring procedure: automated e2e suite (Playwright + device emulator) plus RLS
audit; runs in CI of the private repo.

| Dim | What | Weight | Measurement |
|-----|------|--------|-------------|
| S1 | **Sync correctness** | 30 | Scripted scenario matrix (create/edit/delete on device A → visible on web and device B; offline edits reconcile; DST-crossing timestamps): % of scenarios passing. |
| S2 | **Data isolation** | 20 | RLS audit: authenticated user A attempting every endpoint/table against user B's rows — 0 leaks required for any points (all-or-nothing). |
| S3 | **Durability** | 15 | No data loss across 3 sync round-trips with induced network failures (airplane-mode chaos test); byte-identical note bodies. |
| S4 | **Web review UX** | 15 | Notes searchable across books; filter by book/color/date; export; 10-item checklist. |
| S5 | **Performance** | 10 | Full sync of 1000 notes ≤ 10 s; web notes page LCP ≤ 2.5 s (Vercel analytics). |
| S6 | **Pagination discipline** | 10 | Audit: every Supabase query paginated; synthetic 2500-row account returns complete data. |

**Gates:** S2 = 20 and S3 = 15 are release gates. Target: **S ≥ 85**.

---

## Rubric D — Development Process Health — continuous

Checked at every session close; recorded in the ledger, not scored 0–100.
All must hold:

- [ ] Every completed task in `project_plan.md` has its **Verify** command run
      in the session that closed it, with output recorded (issue comment or
      ledger note).
- [ ] No secrets or local paths in any committed file (`git grep -iE 'sk-(proj|ant)|/home/niko'` on HEAD is empty).
- [ ] Tests green at HEAD (`make test` per phase).
- [ ] Numbers reported with uncertainty (CIs on sampled metrics).
- [ ] `docs/findings.md` updated if anything non-obvious was learned.
