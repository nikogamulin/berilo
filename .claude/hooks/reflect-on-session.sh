#!/usr/bin/env bash
# Stop hook: ask the model to reflect on the session and propose documentation
# updates before ending. Skips when stop_hook_active=true (prevents the model
# from blocking itself on its own reflection turn).
#
# Output contract: prints a JSON object with `decision: "block"` and a
# `reason` field. Claude Code injects `reason` back into the model as a
# system reminder and the turn continues. Any other stdout is ignored.

set -euo pipefail

input=$(cat)
active=$(printf '%s' "$input" | jq -r '.stop_hook_active // false')

if [ "$active" = "true" ]; then
  exit 0
fi

read -r -d '' reason <<'EOF' || true
Before ending this session, reflect on what happened and propose documentation updates while the context is fresh.

GOAL: capture what this session learned so the next session doesn't re-derive it — and route each item to the ONE home Berilo's knowledge substrate defines for it. Keep CLAUDE.md SHORT: §9 is for canonical cross-cutting rules only; prefer the specialized home with at most a one-line pointer.

Scan the session for items NOT already captured, and for each propose the addition AND its correct home (cite the path):

  1. An iteration that landed or was discarded (hypothesis → result → kept/discarded, rubric delta if scored, API cost if any) -> a loops/build/ledger.jsonl row. If real work landed with no ledger row, flag that gap explicitly.

  2. A story's status changed -> docs/project_plan.md checkbox. A story may be checked ONLY if its Verify line was executed and passed THIS session — cite the command and output. If a checkbox was flipped without its Verify run, the gap is the headline. After GitHub issues exist (S0.3), the plan and the issue must agree — cite drift.

  3. A rubric was scored -> loops/build/rubric_scores.jsonl row with commit sha and per-dimension breakdown. Never a score without its defined procedure (docs/rubric.md).

  4. A durable gotcha, working command, extraction quirk, model behavior, device (Boox) behavior, or convention -> docs/findings.md dated bullet. Dedupe: bump/extend an existing bullet rather than adding a parallel one.

  5. A finding that recurred or the user endorsed -> promote to CLAUDE.md §9 as one terse rule (what went wrong → the rule now), and note it stays linked from findings.md.

  6. Translation-quality observations (prompt weaknesses, glossary misses, judge disagreements) -> docs/findings.md now; if they change the scoring procedure itself, propose a docs/rubric.md amendment with a version bump.

  7. Cost events (an unexpectedly expensive run, a cheaper model that sufficed) -> findings.md with the actual numbers.

Rules:
  - If a category has nothing worth adding, say so explicitly — do not invent content.
  - Do NOT edit any doc yourself in the reflection. Show the proposed text + target path and stop; the user decides. Exception worth naming, not acting on: ledger rows belong in the same commit as the work they record — a missing row is the headline.
  - Cite specifics (file paths, exact commands, costs in €, seeds, line numbers) — abstractions rot.
  - Skip anything already captured anywhere, even if phrased differently.
  - Secrets check: if any committed content this session could contain a key or local path, say so first (CLAUDE.md §7 scan).

If you have already produced this reflection this turn, print "Reflection already complete this turn." and stop without repeating it.
EOF

jq -nc --arg reason "$reason" '{decision:"block", reason:$reason}'
