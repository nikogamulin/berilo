"""``berilo doctor`` support: smoke-test every configured LLM provider.

For each provider with a key configured, translates one hardcoded sentence
into the configured target language and reports the result, token usage, and
EUR cost. Backs the ``doctor`` CLI subcommand (wired in ``cli.py``).
"""

from __future__ import annotations

import logging

from berilo.config import Config
from berilo.providers import create_client
from berilo.providers.anthropic import DEFAULT_MODEL as ANTHROPIC_DEFAULT_MODEL
from berilo.providers.openai import DEFAULT_MODEL as OPENAI_DEFAULT_MODEL

logger = logging.getLogger(__name__)

#: Fixed smoke-test sentence, translated via one `complete` call per
#: configured provider.
DOCTOR_SENTENCE = "The library was quiet, but every book in it was shouting."

SUCCESS_EXIT_CODE = 0
FAILURE_EXIT_CODE = 1


def run_doctor(config: Config) -> int:
    """Smoke-test every configured provider with one translation call each.

    For each provider (OpenAI, Anthropic) with an API key present in
    ``config``, sends :data:`DOCTOR_SENTENCE` for translation into
    ``config.target_lang`` and prints the translation, token usage, model,
    and EUR cost. Providers without a configured key are skipped.

    Args:
        config: Resolved configuration holding provider API keys and the
            target language.

    Returns:
        ``0`` if every configured provider succeeded; ``1`` if any
        configured provider failed, or if no provider is configured at all.
    """
    providers: list[tuple[str, str]] = []
    if config.openai_api_key:
        providers.append(("OpenAI", OPENAI_DEFAULT_MODEL))
    if config.anthropic_api_key:
        providers.append(("Anthropic", ANTHROPIC_DEFAULT_MODEL))

    if not providers:
        print(
            "No provider configured. Set OPENAI_API_KEY and/or ANTHROPIC_API_KEY "
            "in .env before running `berilo doctor`."
        )
        return FAILURE_EXIT_CODE

    all_succeeded = True
    for provider_name, model in providers:
        try:
            client = create_client(model, config)
            result = client.complete(
                prompt=(
                    f"Translate the following sentence into {config.target_lang}. "
                    f"Reply with only the translation:\n\n{DOCTOR_SENTENCE}"
                )
            )
        except Exception:
            logger.exception("Doctor smoke test failed for provider %s", provider_name)
            print(f"{provider_name} ({model}): FAILED — see log for details.")
            all_succeeded = False
            continue

        print(f"{provider_name} ({result.model}): {result.text.strip()}")
        print(
            f"  tokens: {result.input_tokens} in / {result.output_tokens} out"
            f"  cost: €{result.cost_eur:.6f}"
        )

    return SUCCESS_EXIT_CODE if all_succeeded else FAILURE_EXIT_CODE
