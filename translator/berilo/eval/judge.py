"""LLM-judge calls backing Rubric T's sampled dimensions.

The judge scores meaning (T2) and fluency (T3) of sampled segment pairs and
screens paragraphs for extraction artifacts (T6). Prompts are versioned plain-
text files under :mod:`berilo.eval.prompts`, loaded via ``importlib.resources``
so the recorded prompt version is always exactly what ran. Every judge reply is
parsed STRICTLY for a ``SCORE: <n>`` line: an unparseable verdict is retried
once, then fails loudly (:class:`JudgeError`) rather than silently scoring 0 —
silent mis-scoring is the failure mode this dimension must never have.
"""

from __future__ import annotations

import logging
import re
from importlib import resources

from berilo.providers.base import LLMClient

logger = logging.getLogger(__name__)

#: Prompt file stems (the version lives in the filename) -> logical role.
MEANING_PROMPT = "meaning_v1"
FLUENCY_PROMPT = "fluency_v1"
SCREEN_PROMPT = "screen_v1"

#: Placeholders substituted into a prompt template (literal-safe, not str.format,
#: so stray braces in prose can never break rendering).
_SOURCE_TOKEN = "<<<SOURCE>>>"
_TARGET_TOKEN = "<<<TARGET>>>"

#: Strict verdict pattern: a ``SCORE:`` line followed by an integer.
_SCORE_RE = re.compile(r"SCORE:\s*(-?\d+)", re.IGNORECASE)

_MEANING_RANGE = range(1, 6)
_FLUENCY_RANGE = range(1, 6)
_SCREEN_RANGE = range(0, 2)


class JudgeError(RuntimeError):
    """Raised when a judge verdict cannot be parsed after a single retry."""


def load_prompt(stem: str) -> str:
    """Load a versioned judge prompt template by file stem.

    Args:
        stem: Prompt file stem, e.g. ``"meaning_v1"``.

    Returns:
        The raw prompt template text.
    """
    return (
        resources.files("berilo.eval.prompts").joinpath(f"{stem}.txt").read_text(encoding="utf-8")
    )


def parse_score(reply: str, allowed: range) -> int | None:
    """Parse a strict ``SCORE: <n>`` verdict, or ``None`` if absent/out of range.

    Args:
        reply: The raw model reply.
        allowed: The permitted integer range for this judge role.

    Returns:
        The parsed integer score, or ``None`` when no in-range ``SCORE:`` line
        is present.
    """
    match = _SCORE_RE.search(reply or "")
    if match is None:
        return None
    value = int(match.group(1))
    return value if value in allowed else None


class Judge:
    """Wraps an :class:`LLMClient` to produce strictly-parsed rubric verdicts.

    Accumulates the EUR cost of every judge call in :attr:`total_cost_eur` and
    the call count in :attr:`calls`, so the eval run can report exactly what it
    spent (mocked to €0 in tests).

    Attributes:
        total_cost_eur: Summed EUR cost of all judge calls made.
        calls: Number of judge API calls made.
    """

    def __init__(self, client: LLMClient) -> None:
        """Initialize the judge.

        Args:
            client: The provider client used for judge completions.
        """
        self._client = client
        self._meaning_tmpl = load_prompt(MEANING_PROMPT)
        self._fluency_tmpl = load_prompt(FLUENCY_PROMPT)
        self._screen_tmpl = load_prompt(SCREEN_PROMPT)
        self.total_cost_eur = 0.0
        self.calls = 0

    def _ask(self, prompt: str, allowed: range) -> int:
        """Send ``prompt`` and return its parsed score, retrying once.

        Args:
            prompt: The fully-rendered judge prompt.
            allowed: Permitted integer range for the verdict.

        Returns:
            The parsed integer score.

        Raises:
            JudgeError: If the verdict is unparseable on both the initial call
                and the single retry.
        """
        last_reply = ""
        for attempt in range(2):
            result = self._client.complete(prompt=prompt)
            self.calls += 1
            self.total_cost_eur += result.cost_eur
            score = parse_score(result.text, allowed)
            if score is not None:
                return score
            last_reply = result.text
            logger.warning("Unparseable judge verdict (attempt %d): %r", attempt + 1, last_reply)
        raise JudgeError(
            f"Judge returned no parseable 'SCORE: <n>' verdict after retry; "
            f"last reply: {last_reply!r}"
        )

    def meaning(self, source: str, target: str) -> int:
        """Score meaning preservation (T2) of a source/target pair, 1-5."""
        prompt = self._meaning_tmpl.replace(_SOURCE_TOKEN, source).replace(_TARGET_TOKEN, target)
        return self._ask(prompt, _MEANING_RANGE)

    def fluency(self, target: str) -> int:
        """Score fluency/naturalness (T3) of a translated segment, 1-5."""
        prompt = self._fluency_tmpl.replace(_TARGET_TOKEN, target)
        return self._ask(prompt, _FLUENCY_RANGE)

    def screen(self, paragraph: str) -> int:
        """Screen a translated paragraph for extraction artifacts (T6): 1 clean, 0 dirty."""
        prompt = self._screen_tmpl.replace(_TARGET_TOKEN, paragraph)
        return self._ask(prompt, _SCREEN_RANGE)
