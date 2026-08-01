# contracts/

Everything in this directory binds more than one repository. It is edited
**here** and nowhere else.

| File | What it fixes | Implemented by |
|---|---|---|
| [`core-spec.md`](core-spec.md) | what "the translation core" is, and the seven surfaces on which the ports must agree with the Python reference | `berilo-android`, `berilo-ios`, `berilo-cloud` |
| [`conformance.md`](conformance.md) | what a port must assert to claim conformance, and how vectors are released and vendored | the same three |
| [`sync_api.md`](sync_api.md) | the sync wire format and the SQL schema | `berilo-android`, `berilo-ios`, `berilo-cloud` |
| [`design_guidelines.md`](design_guidelines.md) | the shared design system: type, colour, spacing, anti-patterns | the same three |
| [`vectors/`](vectors/) | generated conformance vectors — data, not prose | vendored by each port |

## The rule

An implementer that disagrees with a file here has a bug **in the implementer**,
until the file here is changed. Changing it means changing it here first and
then updating every repo listed in the workspace manifest's
`contracts_implemented`. Never the other way around: a wire-format problem fixed
downstream forks the format silently, and the phones and the service stop
agreeing without anything going red.

The one exception to "public repo, public rules": nothing service-private —
keys, project refs, infra topology, cost or revenue figures — may enter these
files. They are world-readable and permanent.

## Vectors are generated, never transcribed

`vectors/` holds output of the Python reference, produced by running it. If a
vector and the code disagree, **the code is right and the vector is stale** —
regenerate; never hand-edit a vector to make a test pass. The same rule binds
the ports in the other direction: if a port and a vector disagree, the port is
wrong.

The vectors carry no book text. The example books are copyrighted and `data/`
is gitignored; every committed vector holds only derived identity — digests,
counts, structural indices — and a test enforces that
(`translator/tests/test_identity_fixture.py`).
