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

## Translator CLI (Phase 1)

```bash
cp .env.example .env             # add your OpenAI or Anthropic key
cd translator && pip install -e .
berilo doctor                    # provider smoke test, one sentence, ~€0
berilo inspect mybook.epub       # extraction preview, no API cost
berilo translate mybook.epub --to sl --dry-run   # cost estimate — always run first
berilo translate mybook.epub --to sl             # translated EPUB alongside the source
```

Requires Python 3.10+. MOBI input additionally requires Calibre
(`ebook-convert`).

## Reader app (Phase 2, Android / Boox)

The reader is an offline EPUB app (Readium-based) with an LLM dictionary,
paragraph interpretation, and notes. It reads whatever the translator CLI
produced — no cloud dependency.

**Install a release build (recommended):**

1. Download the latest `app-release.apk` from the
   [GitHub Releases page](https://github.com/nikogamulin/berilo/releases).
2. On the Boox, enable unknown-source installs: **Settings → Apps → Special
   app access → Install unknown apps**, allow it for the browser or file
   manager you'll install from.
3. Open the downloaded APK on-device and confirm the install.

**Install via adb (USB, for development devices):**

```bash
adb install -r app-release.apk
```

The app never uploads your books; your LLM API key is stored in
EncryptedSharedPreferences on-device only.

## Development

```bash
# Translator
cd translator && pip install -e ".[dev]"
make test && make lint

# Android
cd android
./gradlew assembleDebug test lintDebug   # everyday dev loop
./gradlew assembleRelease                # minified release build (debug-signed
                                          # unless android/keystore.properties exists —
                                          # see keystore.properties.example)
```

## Principles

- Your books and your keys stay on your machine. Only text segments are sent
  to the LLM provider you configure.
- Translation quality is measured, not assumed — see the scoring harness in
  [`docs/rubric.md`](docs/rubric.md).
- No piracy features. You supply files you own.

## License

MIT — Niko Gamulin, PhD. (Phases 1–2 are open source; the optional cloud sync
service is a separate closed-source product.)
