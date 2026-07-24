# berilo-translator

Translator CLI for [Berilo](../README.md): PDF/EPUB/MOBI in, meaning-preserving
translated EPUB out. See [`../docs/project_spec.md`](../docs/project_spec.md)
for the full pipeline design.

## Development

```bash
pip install --user -e ".[dev]"
make test
make lint
berilo --help
```
