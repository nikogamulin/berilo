"""Google Cloud Translation v2 as a *draft* source for LLM post-editing.

Not an :class:`~berilo.providers.base.LLMClient`. It takes a list of segments
and returns a list of translations — no prompt, no markers, no retry ladder,
because there is no model to mis-instruct. That difference is the point: an
MT engine cannot drop a segment or renumber a batch, so the drafting step is
structurally incapable of the failure the marker protocol exists to catch.

**What this is for.** Machine translation alone scores poorly on Rubric T's
fluency dimension; a good LLM alone is strong but pays twice, once to draft and
once to revise. Feeding an MT draft into the *existing* editor pass replaces the
LLM's drafting call rather than adding to it, so the LLM makes one pass instead
of two while the editor still sees the source. On a low-resource target such as
Slovenian — where cheap models are comparatively weak but Google's MT is
strong — that is a plausible quality trade, not merely a cheaper one.

**What it costs, stated plainly, because the arithmetic is counter-intuitive.**
Cloud Translation v2 Basic lists at **USD 20 per million characters** (verify
before relying on it) with the first 500k characters per month free — under one
book. A ~130k-word book is roughly 715k characters, so about **EUR 13** of
Google spend against roughly **EUR 1.45** for the entire two-pass LLM pipeline
it would be assisting. Machine translation here is the *expensive* option, and
it must therefore be justified on measured quality, never on price. Run
``berilo eval`` before adopting it for a book you care about.

Only the **v2** endpoint is implemented: an API key authorizes v2 only, and v3
requires OAuth and a project id.
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Sequence
from dataclasses import dataclass

logger = logging.getLogger(__name__)

#: The v2 endpoint. Keys authorize v2; v3 needs OAuth and a project id.
API_URL = "https://translation.googleapis.com/language/translate/v2"

#: Segments per HTTP request. The endpoint accepts repeated ``q`` parameters;
#: this bounds the URL-encoded body rather than the model's attention, so it can
#: be far larger than an LLM batch.
DEFAULT_MT_BATCH_SIZE = 100

#: USD per million characters, v2 Basic list price as of 2026-08. Verify before
#: relying on absolute costs — this mirrors ``providers/pricing.py``'s caveat.
USD_PER_MILLION_CHARS = 20.0

#: Matches ``providers/pricing.py`` so one run reports one currency.
EUR_PER_USD = 0.92

_CHARS_PER_PRICING_UNIT = 1_000_000

DEFAULT_TIMEOUT_SECONDS = 30


class MachineTranslationError(RuntimeError):
    """Raised when the MT provider cannot produce a 1:1 draft.

    Loud rather than degrading: a draft is an *optimization*, and silently
    continuing without one would change which pipeline produced a book while
    the run still reported the style the user asked for.
    """


@dataclass(frozen=True)
class DraftResult:
    """One batch of machine-translated drafts plus its accounting.

    Attributes:
        texts: Translations aligned 1:1 with the requested segments.
        characters: Source characters billed.
        cost_eur: Cost of this call in EUR.
    """

    texts: list[str]
    characters: int
    cost_eur: float


def cost_eur_for_chars(characters: int) -> float:
    """Return the EUR cost of translating ``characters`` source characters.

    Args:
        characters: Source characters sent.

    Returns:
        Cost in EUR at the v2 Basic list price.
    """
    usd = (characters / _CHARS_PER_PRICING_UNIT) * USD_PER_MILLION_CHARS
    return usd * EUR_PER_USD


class GoogleTranslateClient:
    """Batched Google Cloud Translation v2 client.

    Args:
        api_key: The reader's own Google API key. Never logged.
        batch_size: Segments per HTTP request.
        timeout: Per-request timeout in seconds.
    """

    def __init__(
        self,
        api_key: str,
        *,
        batch_size: int = DEFAULT_MT_BATCH_SIZE,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> None:
        if not api_key:
            raise ValueError("A Google API key is required for machine-translation drafts.")
        self._api_key = api_key
        self.batch_size = batch_size
        self.timeout = timeout

    @property
    def name(self) -> str:
        """Identifier recorded in the cache key alongside the LLM model."""
        return "google-translate-v2"

    def draft(
        self,
        texts: Sequence[str],
        *,
        source_lang: str | None,
        target_lang: str,
    ) -> DraftResult:
        """Translate ``texts``, preserving inline markup, 1:1.

        ``format=html`` is not incidental. Segments carry an inline HTML subset
        (``em``, ``strong``, ``i``, ``b``, ``sub``, ``sup``) that Rubric T5
        scores for retention; under ``format=text`` those tags would be
        translated as prose or dropped.

        Args:
            texts: Source segments.
            source_lang: Source language subtag, or ``None`` to auto-detect.
            target_lang: Target language subtag.

        Returns:
            The :class:`DraftResult`.

        Raises:
            MachineTranslationError: On a transport failure, or if the reply
                does not map 1:1 onto ``texts``.
        """
        if not texts:
            return DraftResult(texts=[], characters=0, cost_eur=0.0)

        fields: list[tuple[str, str]] = [
            ("target", target_lang),
            ("format", "html"),
        ]
        if source_lang:
            fields.append(("source", source_lang))
        fields.extend(("q", text) for text in texts)

        body = urllib.parse.urlencode(fields).encode("utf-8")
        # The key travels as a query parameter because that is what v2 accepts.
        # It is never logged: every diagnostic below names counts, not the URL.
        url = f"{API_URL}?key={urllib.parse.quote(self._api_key)}"
        request = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )

        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            # exc.read() may carry the request URL, and the URL carries the key.
            # Report the status only.
            raise MachineTranslationError(
                f"Google Translate rejected a batch of {len(texts)} segments " f"(HTTP {exc.code})."
            ) from None
        except urllib.error.URLError as exc:
            raise MachineTranslationError(
                f"Could not reach Google Translate for a batch of {len(texts)} segments: "
                f"{exc.reason}"
            ) from None

        translations = payload.get("data", {}).get("translations", [])
        if len(translations) != len(texts):
            raise MachineTranslationError(
                f"Google Translate returned {len(translations)} translations "
                f"for {len(texts)} segments."
            )

        drafts = [entry.get("translatedText", "") for entry in translations]
        if any(not draft.strip() for draft in drafts):
            raise MachineTranslationError(
                "Google Translate returned an empty translation for at least one segment."
            )

        characters = sum(len(text) for text in texts)
        return DraftResult(
            texts=drafts,
            characters=characters,
            cost_eur=cost_eur_for_chars(characters),
        )
