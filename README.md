# Berilo

Read books in your own language.

Berilo translates books (PDF / EPUB / MOBI) into your language using
inexpensive LLMs — built to preserve meaning, not just words — and gives you a
reading app made for people who actually read: an in-context LLM dictionary,
interpretation of dense paragraphs, highlights and notes.

**Bring your own API key.** Defaults use the cheapest models that do the job
(≈ €0.50 to translate a full book); every model is user-selectable.

## Status

| Phase | What | Status |
|-------|------|--------|
| 1 | Translator CLI (`translator/`) — book in, translated EPUB out | in progress |
| 2 | Android reader (`android/`) — offline, e-ink friendly (Boox) | planned |
| 3 | Cloud sync + web note review (closed-source service) | planned |

Plan and objective acceptance criteria: [`docs/project_plan.md`](docs/project_plan.md).
Quality rubrics the project optimizes: [`docs/rubric.md`](docs/rubric.md).

## Quick start (Phase 1)

```bash
cp .env.example .env      # add your OpenAI or Anthropic key
cd translator && pip install -e .
berilo translate mybook.epub --to sl --dry-run   # cost estimate first
berilo translate mybook.epub --to sl
```

Requires Python 3.10+. MOBI input additionally requires Calibre
(`ebook-convert`).

## Principles

- Your books and your keys stay on your machine. Only text segments are sent
  to the LLM provider you configure.
- Translation quality is measured, not assumed — see the scoring harness in
  [`docs/rubric.md`](docs/rubric.md).
- No piracy features. You supply files you own.

## License

MIT — Niko Gamulin, PhD. (Phases 1–2 are open source; the optional cloud sync
service is a separate closed-source product.)
