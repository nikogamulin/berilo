# Findings register (Tier 2)

> Durable, session-discovered knowledge: gotchas, working commands,
> conventions, stuck-patterns. **Scan before debugging or running non-obvious
> commands.** Promote to CLAUDE.md §9 once a finding recurs or is endorsed.
> Format: `- [YYYY-MM-DD] finding — evidence/command`.

- [2026-07-24] Example set is **2 PDF + 1 EPUB, no MOBI** despite "pdf or mobi"
  in the brief — MOBI support goes through `ebook-convert` with a converted
  fixture for tests.
- [2026-07-24] Both example PDFs have text layers (no OCR pass needed), but
  *This Is How They Tell Me the World Ends* is OCR-sourced: running headers and
  roman page numbers appear inline in extracted text (`"PROLOGUE \nXix \n"`).
  Normalizer must strip header/footer lines by repeating-pattern detection, not
  just regex.
- [2026-07-24] Environment (TensorForgeX): Python 3.10.12, Node v22.13.1,
  PyMuPDF 1.27.2 installed system-wide, `pdftotext` and Calibre
  `ebook-convert` on PATH.
- [2026-07-24] `~/.local/bin/gh` is a stray PyPI package (`gh` 0.0.4) shadowing
  the real GitHub CLI — always call `/usr/bin/gh`. Installed gh is 2.4.0
  (Ubuntu): no `gh label` command, `gh issue close` broken (git exit 128) —
  use `gh api repos/nikogamulin/berilo/...` for labels, milestones, issue
  state changes.
- [2026-07-24] The `pytest` shim on PATH (`~/.local/bin/pytest`) is shebang'd
  to python3.12 while `pip install --user` targets python3 (3.10.12) — bare
  `pytest` can't see the installed package. Always run `python3 -m pytest`
  and `python3 -m black` (encoded in `translator/Makefile`); `ruff` is a
  native binary and unaffected.
- [2026-07-24] `epubcheck` 5.3.0 installed to `~/.local/bin/epubcheck`
  (wrapper over jar in `~/.local/share/epubcheck-5.3.0/`, Java 21 present);
  `ebook-convert` and `pdftotext` are on PATH.
- [2026-07-24] **gpt-5-mini bills hidden reasoning tokens as output**: the
  doctor one-sentence smoke used 479 output tokens for a ~15-token visible
  translation (≈15–30× inflation on short outputs). S1.5 `--dry-run` cost
  estimates must include a reasoning-token multiplier or they will
  underestimate badly. claude-haiku-4-5 returned 25 output tokens for the
  same sentence.
- [2026-07-24] Synthetic API keys in tests must NOT use real vendor key
  prefixes ("sk-" + proj/ant forms) or the §7 secret scan false-positives — use
  `test-openai-key-...` style. Real vendor exception types for retry tests
  are constructed with a fake `httpx.Response(429, request=...)`; OpenAI and
  Anthropic SDK clients construct offline with dummy keys (no network at
  init).
- [2026-07-24] `data/` exists only in the main checkout, not in agent
  worktrees (gitignored ⇒ not shared): `data/`-gated integration tests must
  skip gracefully; run real-book Verify numbers from the main checkout.
- [2026-07-24] EPUB3 `<nav>` documents (TOC/landmarks) must be excluded from
  segment extraction or their `<li>/<a>` structure injects a duplicate TOC
  as junk segments at chapter 0 (handled in `normalize/epub.py`).
- [2026-07-24] *The New Rules of War* back matter is huge: Notes+Index+
  Bibliography ≈ 1086 of 2309 segments (47%; Index alone 757). Translating
  back matter by default would roughly double cost for near-zero reader
  value — S1.5 should expose `--skip-back-matter` (or similar) and dry-run
  estimates should show per-chapter breakdown.
- [2026-07-24] `data/` is gitignored and holds copyrighted books — never
  commit, never upload contents anywhere except segment batches to the
  configured LLM API during translation.
