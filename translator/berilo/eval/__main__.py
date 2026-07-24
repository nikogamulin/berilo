"""``python -m berilo.eval`` entry point — the Rubric T scoring procedure.

``docs/rubric.md`` names ``python -m berilo.eval <book> --sample 40 --seed 42``
as the canonical Rubric T command; it runs the exact same logic as
``berilo eval``.
"""

from __future__ import annotations

from berilo.cli import eval_


def main() -> None:
    """Invoke the ``eval`` command as a standalone program."""
    eval_(prog_name="python -m berilo.eval")


if __name__ == "__main__":
    main()
