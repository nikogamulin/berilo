"""Seeded sampling and percentile bootstrap confidence intervals.

Rubric T reports every sampled metric with a 95 % bootstrap CI (CLAUDE.md §4:
"numbers with uncertainty"). Everything here is pure ``random``-stdlib — no
numpy dependency — so the harness stays deployable anywhere the CLI runs, and
every draw is reproducible from an explicit integer seed.
"""

from __future__ import annotations

import random
from collections.abc import Callable, Sequence

#: Default number of bootstrap resamples (the rubric requires >= 1000).
DEFAULT_RESAMPLES = 2000

#: Default confidence level for reported intervals.
DEFAULT_CI = 0.95


def select_indices(n_eligible: int, sample_size: int, seed: int) -> list[int]:
    """Choose a deterministic, sorted subset of ``range(n_eligible)``.

    Uses a freshly seeded :class:`random.Random` so the selection depends only
    on ``n_eligible``, ``sample_size`` and ``seed`` — never on global RNG state.
    The result is sorted so downstream ordering (judge-call order, JSON output)
    is stable.

    Args:
        n_eligible: Size of the pool to sample from.
        sample_size: Desired number of items. If it meets or exceeds
            ``n_eligible``, every index is returned.
        seed: Integer seed for reproducible selection.

    Returns:
        A sorted list of distinct indices in ``[0, n_eligible)``.
    """
    if n_eligible <= 0 or sample_size <= 0:
        return []
    if sample_size >= n_eligible:
        return list(range(n_eligible))
    rng = random.Random(seed)
    return sorted(rng.sample(range(n_eligible), sample_size))


def _quantile(ordered: Sequence[float], q: float) -> float:
    """Linear-interpolation quantile of an already-sorted sequence.

    Args:
        ordered: Values sorted ascending (non-empty).
        q: Quantile in ``[0, 1]``.

    Returns:
        The interpolated quantile value.
    """
    if len(ordered) == 1:
        return ordered[0]
    pos = q * (len(ordered) - 1)
    low = int(pos)
    high = min(low + 1, len(ordered) - 1)
    frac = pos - low
    return ordered[low] * (1 - frac) + ordered[high] * frac


def percentile_ci(samples: Sequence[float], ci: float = DEFAULT_CI) -> tuple[float, float]:
    """Return the ``(low, high)`` percentile interval of bootstrap statistics.

    Args:
        samples: Bootstrap statistic values.
        ci: Central confidence level (e.g. ``0.95``).

    Returns:
        The lower and upper percentile bounds. For an empty input, ``(0.0, 0.0)``.
    """
    if not samples:
        return (0.0, 0.0)
    ordered = sorted(samples)
    lo_q = (1.0 - ci) / 2.0
    hi_q = 1.0 - lo_q
    return (_quantile(ordered, lo_q), _quantile(ordered, hi_q))


def bootstrap_ci(
    statistic: Callable[[Sequence[int]], float],
    n: int,
    *,
    n_resamples: int = DEFAULT_RESAMPLES,
    seed: int = 42,
    ci: float = DEFAULT_CI,
) -> tuple[float, float]:
    """Percentile bootstrap CI of an arbitrary statistic over ``n`` items.

    Draws ``n_resamples`` bootstrap samples of ``n`` indices (with replacement)
    from ``range(n)`` and evaluates ``statistic`` on each, then returns the
    central ``ci`` percentile interval of those values. Passing the resampled
    *indices* (rather than values) lets callers recompute a compound statistic
    — e.g. a weighted rubric total from several paired per-sample arrays — from
    one shared resample.

    Args:
        statistic: Maps a list of resampled indices to a scalar statistic.
        n: Number of underlying observations.
        n_resamples: Number of bootstrap resamples (rubric requires >= 1000).
        seed: Integer seed for reproducible resampling.
        ci: Central confidence level.

    Returns:
        The ``(low, high)`` bounds of the bootstrap CI. If ``n == 0`` the
        statistic is evaluated once on an empty sample for both bounds.
    """
    if n <= 0:
        value = statistic([])
        return (value, value)
    rng = random.Random(seed)
    stats = [statistic([rng.randrange(n) for _ in range(n)]) for _ in range(n_resamples)]
    return percentile_ci(stats, ci)


def mean(values: Sequence[float]) -> float:
    """Arithmetic mean, or ``0.0`` for an empty sequence."""
    return sum(values) / len(values) if values else 0.0
