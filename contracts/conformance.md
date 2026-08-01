# Conformance

What a port must assert to claim it implements [`core-spec.md`](core-spec.md),
how vectors are generated and released, and — stated plainly, because the gaps
are the useful part — which surfaces are actually gated today.

---

## 1. The shape of a conformance test

A conformance test reads a **committed vector** produced by the Python
reference and asserts the port reproduces it. It never recomputes the expected
value in the port's own language, because a port that agrees with itself proves
nothing.

Three rules, all of which have already been violated somewhere and cost a
session:

1. **Vectors are generated, never transcribed.** A generator script runs the
   real Python and writes the file. A vector typed out by hand is a test of
   somebody's copying, not of the port.
2. **When a vector and the port disagree, the port is wrong.** Never update a
   vector to make a suite go green. If the reference genuinely changed, the
   change lands in `translator/` first and the vector is *regenerated*, not
   edited.
3. **A vector carries no book text.** The corpus is copyrighted and `data/` is
   gitignored; vectors are committed. Only derived values — digests, counts,
   indices — may appear. Synthetic books, defined in the generator, are the
   exception and the only place literal text belongs.

## 2. Coverage today

Honest state, not target state. "Gated" means a test fails when the port
diverges.

| # | Surface | `berilo-cloud` | `berilo-android` | `berilo-ios` |
|---|---|---|---|---|
| 1 | Identity | by construction | **gated** — `IdentityFixtureTest`, `SegmentIdentityTest` | **gated** — `IdentityFixtureTests` |
| 2 | Prompts | by construction | **gated** — `PromptTextTest` *(see §5)* | port present, not gated |
| 3 | Markers & batching | by construction | **gated** — `BatchPlanVectorsTest` (batching); markers not gated | vectors published (`v2`), port not yet asserting; **batch composition is a declared exception** (`core-spec.md` §3) |
| 4 | Models & pricing | by construction | not gated | not gated |
| 5 | Normalization | by construction | not gated | not gated |
| 6 | EPUB writer determinism | by construction | **gated** — `EpubWriterByteIdentityTest` | in progress |
| 7 | Cache keys | by construction | not gated | not gated |

"By construction" means `berilo-cloud` imports `berilo-translator` from this
repo rather than reimplementing it, so the only thing it can get wrong is
*which commit it pinned*. That check is a version assertion, not a vector.

## 3. Vector releases

Vectors live in `vectors/v<N>/` with a `manifest.json` recording
`vectors_version`, the `pkg_version` they were generated against, and the
release tag.

- A change that alters any vector's bytes is a **version bump**: a new
  `vectors/v<N+1>/` directory, not an edit in place. Ports move over
  deliberately.
- A release is a git tag on this repository, `contracts-v<N>`, where `<N>` is
  the directory integer.
- `v2` is the first release to carry Surface 3 (`batch_plan/`). It does **not**
  supersede `v1`: the identity and assemble vectors still live there, and a port
  vendoring `v1` keeps working. Moving to `v2` is what buys the batching gate.
- Each port **vendors** its own copy under its test resources, so its suite runs
  offline. That copy going stale is the failure mode this numbering exists to
  make visible.

Freshness is a CI-only check — the one place network access is assumed — in
which a port resolves this repo's newest `contracts-v*` tag and fails if its
vendored `vectors_version` is older. Locally, `bin/berilo doctor` in the
workspace repo is the equivalent, run on demand. A local test run never reaches
the network.

## 4. Generators

`gen/` holds the scripts that execute the Python reference and emit vectors.
They live here because they *run the reference*, and a generator in another
repository can only reach a checkout that has already gone stale.

| Generator | Emits | Surface |
|---|---|---|
| `gen/generate_assemble_vectors.py` | `vectors/v1/assemble/` | 6 |
| `berilo.identity_fixture` (a package module, since its output is also tested here) | `vectors/v1/identity/` | 1 |
| `gen/generate_batch_plan_vectors.py` | `vectors/v2/batch_plan/` | 3 |

**Still outside this directory,** and to be moved when the repository holding
them has committed its in-flight work: `berilo-ios/tools/gen_identity_fixtures.py`,
`gen_model_fixtures.py`, `gen_prompt_fixtures.py`, `gen_bookhash_gate.py`,
`gen_epub_writer_gate.py`, `gen_zip_gate.py`, `gen_html5_entities.py`,
`gen_synthetic_epubs.py`, `gen_cover_epubs.py`, `dump_segments.py`. Three of
those are currently untracked in that repo, so they cannot be moved without
committing work in progress that is not this reorganization's to commit.

## 5. Known gaps, named

- **`python_prompts.json` has no generator.** `berilo-android`'s prompt vector
  at `app/src/test/resources/prompts/python_prompts.json` was produced ad hoc
  and committed; nothing in any repository can reproduce it. It therefore fails
  rule 1 of §1: the gate is real, but it cannot be refreshed when
  `translator/berilo/prompts.py` changes, so it will silently pin a prompt that
  the reference has moved past. Writing `gen/gen_prompt_vectors.py` is the fix.
- **Surfaces 4, 5 and 7 have no vectors at all** in any port. They are specified
  in `core-spec.md` and enforced by nothing. Surface 4 is the one to do first:
  it is the only surface whose divergence spends the user's money without
  asking. Surface 3 gained `vectors/v2/batch_plan/` on 2026-08-01, which is what
  turned a suspected divergence into a measured one — the ports still have to
  vendor them and assert.
- **`vectors/v2/batch_plan/` records `waves[].batches`, which iOS is excepted
  from** (`core-spec.md` §3). A port skipping that one field must still assert
  every other field in the file. The risk this creates is specific and worth
  naming: an iOS suite that skipped the whole vector, rather than the one field,
  would silently stop gating the wave rules iOS *does* satisfy — which is how an
  exception turns into a hole.
- **The regenerate-and-diff CI gate does not exist here.** This repo should
  regenerate every vector on CI and fail if the tree differs, which is what
  makes it impossible for a vector to drift from the Python that produced it.
  Until it lands, that guarantee rests on whoever remembers to rerun the
  generator.
