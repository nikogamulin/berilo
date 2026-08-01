"""The reference must reproduce its own committed batching vectors.

This is not circular. The vectors are what the Kotlin and Swift ports assert
against, so this suite is the tripwire for the case that must never pass
silently: `berilo/plan.py` changes, the committed vectors no longer describe
it, and the ports go on being gated against a stale description of the
reference.

When this fails, the fix is to regenerate — never to edit a vector
(`contracts/conformance.md` §1 rule 2) — and regenerating into the *same*
directory is only correct while `v2` is unreleased. Once `contracts-v2` is
tagged, a change that alters these bytes is a version bump to `v3`.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
_VECTOR_DIR = _REPO_ROOT / "contracts" / "vectors" / "v2" / "batch_plan"
_GEN_DIR = _REPO_ROOT / "contracts" / "gen"


def _load_generator():
    """Import the generator module that lives outside the package."""
    if str(_GEN_DIR) not in sys.path:
        sys.path.insert(0, str(_GEN_DIR))
    import generate_batch_plan_vectors

    return generate_batch_plan_vectors


def _case_names() -> list[str]:
    if not _VECTOR_DIR.exists():
        return []
    return sorted(
        path.stem.split(".", 1)[1]
        for path in _VECTOR_DIR.glob("synthetic.*.json")
        if "inventory" not in path.name
    )


pytestmark = pytest.mark.skipif(
    not _VECTOR_DIR.exists(), reason="batch-plan vectors have not been generated"
)


@pytest.mark.parametrize("case_name", _case_names())
def test_reference_reproduces_its_committed_vector(case_name: str) -> None:
    generator = _load_generator()
    expected = json.loads((_VECTOR_DIR / f"synthetic.{case_name}.json").read_text("utf-8"))
    case = next(entry for entry in generator.CASES if entry[0] == case_name)
    assert generator.case_vector(generator._synthetic_book(), case) == expected


def test_every_case_has_a_committed_vector() -> None:
    """A case added to the generator but never regenerated gates nothing."""
    generator = _load_generator()
    assert sorted(entry[0] for entry in generator.CASES) == _case_names()


def test_vectors_carry_no_book_text() -> None:
    """`conformance.md` §1 rule 3: only derived values may be committed.

    Asserted mechanically rather than trusted, because the generator is one
    careless field away from writing prose into a world-readable repository.
    """
    for path in _VECTOR_DIR.glob("*.json"):
        body = path.read_text("utf-8")
        assert "paragraph" not in body, f"{path.name} carries segment text"


def test_the_manifest_pins_the_vectors_version() -> None:
    manifest = json.loads((_VECTOR_DIR.parent / "manifest.json").read_text("utf-8"))
    assert manifest["vectors_version"] == 2
    assert "batch_plan" in manifest["surfaces"]
