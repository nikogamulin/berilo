"""Per-model USD pricing table and USD→EUR cost conversion.

Pricing as of 2026-07, verify before relying on absolute costs.
"""

from __future__ import annotations

#: USD → EUR conversion rate. Pricing as of 2026-07, verify before relying on
#: absolute costs.
EUR_PER_USD = 0.92

#: Number of tokens per pricing unit (vendor list prices are per-million-token).
_TOKENS_PER_PRICING_UNIT = 1_000_000

#: model -> (USD per million input tokens, USD per million output tokens).
#: Pricing as of 2026-07, verify before relying on absolute costs.
_PRICING_USD_PER_MILLION_TOKENS: dict[str, tuple[float, float]] = {
    "gpt-5-mini": (0.25, 2.00),
    "gpt-5": (1.25, 10.00),
    "gpt-5-nano": (0.05, 0.40),
    "claude-sonnet-4-5": (3.00, 15.00),
    "claude-haiku-4-5": (1.00, 5.00),
    "claude-opus-4-1": (15.00, 75.00),
}


def cost_eur(model: str, input_tokens: int, output_tokens: int) -> float:
    """Compute the EUR cost of a completion call.

    Args:
        model: Model identifier as billed (must be a key in the pricing
            table above).
        input_tokens: Number of input (prompt) tokens billed.
        output_tokens: Number of output (completion) tokens billed.

    Returns:
        Cost of the call in EUR.

    Raises:
        ValueError: If ``model`` has no known pricing entry. The message
            lists all known models so the caller can pick a supported one.
    """
    if model not in _PRICING_USD_PER_MILLION_TOKENS:
        known_models = ", ".join(sorted(_PRICING_USD_PER_MILLION_TOKENS))
        raise ValueError(
            f"No pricing entry for model '{model}'. Known models: {known_models}. "
            "Add it to berilo/providers/pricing.py if it's a new, supported model."
        )
    input_usd_per_million, output_usd_per_million = _PRICING_USD_PER_MILLION_TOKENS[model]
    usd_cost = (input_tokens / _TOKENS_PER_PRICING_UNIT) * input_usd_per_million + (
        output_tokens / _TOKENS_PER_PRICING_UNIT
    ) * output_usd_per_million
    return usd_cost * EUR_PER_USD
