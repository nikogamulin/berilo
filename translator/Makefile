.PHONY: test lint

# Invoke via `python3 -m` rather than the bare console scripts: on this
# environment the `pytest` shim on PATH resolves to a different Python
# interpreter than the one the package is installed under (see
# docs/findings.md), which breaks module discovery.
test:
	python3 -m pytest -q

lint:
	python3 -m black --check .
	ruff check .
