"""Runtime configuration: API keys and model defaults from ``.env`` / flags.

Environment variable names match ``.env.example`` exactly:
``OPENAI_API_KEY``, ``ANTHROPIC_API_KEY``, ``BERILO_TRANSLATION_MODEL``,
``BERILO_JUDGE_MODEL``, ``BERILO_TARGET_LANG``.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import dotenv_values

#: Cheapest model that does the job (CLAUDE.md §2) — the default for both
#: translation and judge calls unless overridden.
DEFAULT_TRANSLATION_MODEL = "gpt-5-mini"
DEFAULT_JUDGE_MODEL = "gpt-5-mini"
DEFAULT_TARGET_LANG = "sl"

ENV_OPENAI_API_KEY = "OPENAI_API_KEY"
ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
ENV_TRANSLATION_MODEL = "BERILO_TRANSLATION_MODEL"
ENV_JUDGE_MODEL = "BERILO_JUDGE_MODEL"
ENV_TARGET_LANG = "BERILO_TARGET_LANG"


@dataclass
class Config:
    """Resolved runtime configuration.

    API key fields use ``field(repr=False)`` so ``repr(config)`` — and
    anything that logs a ``Config`` — never exposes secret material.

    Attributes:
        openai_api_key: The user's own OpenAI API key, if configured.
        anthropic_api_key: The user's own Anthropic API key, if configured.
        translation_model: Default model for translation calls.
        judge_model: Default model for eval/judge calls.
        target_lang: Default target language code (BCP-47 or short tag).
    """

    openai_api_key: str | None = field(default=None, repr=False)
    anthropic_api_key: str | None = field(default=None, repr=False)
    translation_model: str = DEFAULT_TRANSLATION_MODEL
    judge_model: str = DEFAULT_JUDGE_MODEL
    target_lang: str = DEFAULT_TARGET_LANG


def load_config(env_file: str | Path | None = None, **overrides: object) -> Config:
    """Load configuration with precedence: default < env/.env < overrides.

    Reads from the process environment by default. If ``env_file`` is given,
    values from that file take precedence over the process environment (but
    never mutate ``os.environ`` — the file is parsed in isolation via
    :func:`dotenv.dotenv_values`), which keeps this function pure and safe to
    call repeatedly in tests without leaking state across test cases or
    picking up a repository's real ``.env`` implicitly.

    Args:
        env_file: Optional path to a ``.env``-style file. When omitted, only
            the process environment is consulted (no implicit ``.env``
            discovery).
        **overrides: Explicit keyword overrides (e.g. from CLI flags), one
            per :class:`Config` field. ``None`` values are ignored so a
            caller can pass through argparse/click defaults unconditionally.

    Returns:
        The resolved :class:`Config`.
    """
    file_values: dict[str, str | None] = dotenv_values(env_file) if env_file is not None else {}

    def _get(name: str, default: str) -> str:
        file_value = file_values.get(name)
        if file_value:
            return file_value
        return os.environ.get(name) or default

    def _get_optional(name: str) -> str | None:
        file_value = file_values.get(name)
        if file_value:
            return file_value
        return os.environ.get(name) or None

    resolved: dict[str, object] = {
        "openai_api_key": _get_optional(ENV_OPENAI_API_KEY),
        "anthropic_api_key": _get_optional(ENV_ANTHROPIC_API_KEY),
        "translation_model": _get(ENV_TRANSLATION_MODEL, DEFAULT_TRANSLATION_MODEL),
        "judge_model": _get(ENV_JUDGE_MODEL, DEFAULT_JUDGE_MODEL),
        "target_lang": _get(ENV_TARGET_LANG, DEFAULT_TARGET_LANG),
    }
    resolved.update({key: value for key, value in overrides.items() if value is not None})
    return Config(**resolved)
