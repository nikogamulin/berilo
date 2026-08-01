"""Tests for the evaluation corpus builder.

The corpus exists so a translation change can be scored in minutes for cents
instead of hours for euros. Two properties make it worth trusting, and both are
asserted here: the selection is **deterministic** (the same books and seed give
the same sample, or comparing two runs means nothing) and the committed
manifest **carries no book text** (the sources are copyrighted and the manifest
is public).
"""

from __future__ import annotations

import json

import pytest

from berilo.eval import corpus
from berilo.eval.corpus import Candidate, SourceBook
from berilo.models import Book, Segment, SegmentType, make_segment_id

#: Keys that could carry prose out of a copyrighted book. The manifest is
#: committed to a public repository, so any of these appearing in one is a
#: licensing defect, not a style one.
FORBIDDEN_KEYS = frozenset({"text", "title", "chapter_title", "alt", "source_path", "source_href"})


def make_segment(text: str, chapter: int, position: int, kind=SegmentType.PARAGRAPH):
    """Build one segment with a real id."""
    return Segment(
        id=make_segment_id(text, chapter, position),
        type=kind,
        text=text,
        chapter_index=chapter,
        chapter_title=f"Chapter {chapter}",
        position=position,
    )


def synthetic_book(slug: str, n: int, *, start: int = 0) -> SourceBook:
    """Build a `SourceBook` whose candidates cover every stratum and band."""
    source = SourceBook(
        slug=slug,
        title=slug.replace("-", " ").title(),
        authors=["A. Author"],
        source_format="pdf",
        book_hash=f"hash-{slug}",
        total_segments=n,
    )
    flavours = [
        "In 1994 the NATO report noted a rise of 12 percent across the region.",
        "He wrote that “the system is the cause” — and the parenthetical (a long aside) followed.",
        "The informa- tion was split across a line, as extraction some- times leaves it.",
        "Consider the following:",
        "A plain sentence with nothing unusual about it whatsoever at all here.",
        "Naïve café résumé models [12] were cited in (2001) by several authors.",
    ]
    for i in range(n):
        base = flavours[i % len(flavours)]
        # Vary length so all three bands are populated.
        repeat = 1 + (i % 3) * 4
        text = " ".join([base] * repeat) + f" Sentence {start + i} padding word."
        if base.endswith(":"):
            # `colon_lead` matches only at end of text, so the padding must not
            # bury the colon — otherwise this flavour silently contributes
            # nothing and the coverage assertions test an empty stratum.
            text = text[:-1] + ":"
        words = len(text.split())
        source.candidates.append(
            Candidate(
                book_slug=slug,
                chapter_index=1 + i // 10,
                position=i,
                text=text,
                words=words,
                band=corpus.band_of(words),
                strata=corpus.strata_of(text),
                segment_hash=f"seg-{slug}-{i}",
                source_format="pdf",
            )
        )
    return source


@pytest.fixture
def sources():
    """Three books of different sizes, as the real corpus has."""
    return [
        synthetic_book("alpha", 300),
        synthetic_book("beta", 120, start=1000),
        synthetic_book("gamma", 60, start=2000),
    ]


class TestSlugAndTitle:
    def test_clean_title_strips_download_site_markers(self):
        """A public manifest must not advertise where a file came from."""
        assert corpus.clean_title("_OceanofPDF.com_Complex_Adaptive_Systems") == (
            "Complex Adaptive Systems"
        )
        assert (
            corpus.clean_title("Thinking in Systems (z-library.sk, 1lib.sk, z-lib.sk)")
            == "Thinking in Systems"
        )

    def test_slugify_is_ascii_and_bounded(self):
        assert corpus.slugify("The Book of Why") == "the-book-of-why"
        assert corpus.slugify("Naïve Café Résumé") == "naive-cafe-resume"
        assert len(corpus.slugify(" ".join(["word"] * 30)).split("-")) <= 8

    def test_slugify_never_returns_empty(self):
        assert corpus.slugify("!!! ???") == "untitled"


class TestStrata:
    def test_every_stratum_is_detectable(self):
        """A stratum nothing can match would be a silently dead quota."""
        samples = {
            "digits": "there were 4 of them",
            "year": "back in 1994 it began",
            "acronym": "the NATO report",
            "quotes": "he said “no” to that",
            "dash": "a pause — then nothing",
            "parenthetical": "a claim (with an aside) here",
            "citation": "as shown [12]",
            "hyphen_break": "the informa- tion arrived",
            "non_ascii": "a café in town",
            "colon_lead": "consider the following:",
        }
        assert set(samples) == set(corpus.STRATA)
        for name, text in samples.items():
            assert name in corpus.strata_of(text), name

    def test_plain_prose_carries_no_stratum(self):
        assert corpus.strata_of("a plain sentence with nothing unusual") == frozenset()

    def test_band_of_covers_every_length(self):
        assert corpus.band_of(corpus.MIN_WORDS) == "short"
        assert corpus.band_of(34) == "short"
        assert corpus.band_of(35) == "medium"
        assert corpus.band_of(79) == "medium"
        assert corpus.band_of(80) == "long"
        assert corpus.band_of(10_000) == "long"


class TestEligibility:
    def test_excludes_short_non_prose_and_matter(self):
        segments = [
            make_segment("A heading", 0, 0, SegmentType.HEADING),
            make_segment("too short to judge", 1, 0),
            make_segment(" ".join(["word"] * 40), 1, 1),
            make_segment(" ".join(["word"] * 40), 2, 0, SegmentType.CAPTION),
        ]
        book = Book(
            title="T",
            authors=[],
            language="en",
            source_path="x.epub",
            source_format="epub",
            segments=segments,
        )
        kept = corpus.eligible(book)
        assert [s.position for s in kept] == [1]

    def test_front_and_back_matter_are_dropped(self):
        long_text = " ".join(["word"] * 40)
        segments = [
            Segment(
                id=make_segment_id(long_text, 0, 0),
                type=SegmentType.PARAGRAPH,
                text=long_text,
                chapter_index=0,
                chapter_title="Acknowledgments",
                position=0,
            ),
            Segment(
                id=make_segment_id(long_text, 1, 0),
                type=SegmentType.PARAGRAPH,
                text=long_text,
                chapter_index=1,
                chapter_title="Chapter One",
                position=0,
            ),
            Segment(
                id=make_segment_id(long_text, 2, 0),
                type=SegmentType.PARAGRAPH,
                text=long_text,
                chapter_index=2,
                chapter_title="Index",
                position=0,
            ),
        ]
        book = Book(
            title="T",
            authors=[],
            language="en",
            source_path="x.epub",
            source_format="epub",
            segments=segments,
        )
        assert [s.chapter_index for s in corpus.eligible(book)] == [1]


class TestSelection:
    def test_is_deterministic(self, sources):
        """The whole point: two runs must agree, or a comparison means nothing."""
        first = corpus.select(sources, "smoke")
        second = corpus.select(sources, "smoke")
        assert [(c.book_slug, c.position) for c in first] == [
            (c.book_slug, c.position) for c in second
        ]

    def test_a_different_seed_gives_a_different_sample(self, sources):
        a = corpus.select(sources, "smoke", seed=42)
        b = corpus.select(sources, "smoke", seed=43)
        assert {(c.book_slug, c.position) for c in a} != {(c.book_slug, c.position) for c in b}

    def test_hits_the_tier_size_exactly(self, sources):
        for tier, size in corpus.TIERS.items():
            assert len(corpus.select(sources, tier)) == size, tier

    def test_selects_nothing_twice(self, sources):
        chosen = corpus.select(sources, "standard")
        keys = [(c.book_slug, c.chapter_index, c.position) for c in chosen]
        assert len(keys) == len(set(keys))

    def test_every_book_reaches_its_floor(self, sources):
        """A large book must not crowd out a small one."""
        for tier in corpus.TIERS:
            chosen = corpus.select(sources, tier)
            per_book = {s.slug: 0 for s in sources}
            for candidate in chosen:
                per_book[candidate.book_slug] += 1
            for slug, count in per_book.items():
                assert count >= corpus.BOOK_FLOOR[tier], f"{tier}/{slug}: {count}"

    def test_every_length_band_is_represented(self, sources):
        for tier in corpus.TIERS:
            bands = {c.band for c in corpus.select(sources, tier)}
            assert bands == {name for name, _, _ in corpus.LENGTH_BANDS}, tier

    def test_rare_strata_reach_their_floor(self, sources):
        """Coverage is the reason selection is stratified rather than uniform."""
        for tier in corpus.TIERS:
            counts = {}
            for candidate in corpus.select(sources, tier):
                for name in candidate.strata:
                    counts[name] = counts.get(name, 0) + 1
            for name in corpus.STRATA:
                assert counts.get(name, 0) >= corpus.STRATUM_FLOOR[tier], f"{tier}/{name}"

    def test_output_is_in_document_order_per_book(self, sources):
        chosen = corpus.select(sources, "standard")
        keys = [(c.book_slug, c.chapter_index, c.position) for c in chosen]
        assert keys == sorted(keys)

    def test_an_unknown_tier_raises(self, sources):
        with pytest.raises(KeyError):
            corpus.select(sources, "enormous")


class TestSampleBook:
    def test_one_chapter_per_source_book_with_a_heading(self, sources):
        chosen = corpus.select(sources, "smoke")
        book = corpus.build_sample_book(chosen, sources, "smoke")
        assert book.chapter_count == len({c.book_slug for c in chosen})
        headings = [s for s in book.segments if s.type is SegmentType.HEADING]
        assert len(headings) == book.chapter_count
        assert all(s.heading_level == 1 for s in headings)

    def test_carries_every_selected_paragraph_unchanged(self, sources):
        chosen = corpus.select(sources, "smoke")
        book = corpus.build_sample_book(chosen, sources, "smoke")
        paragraphs = [s.text for s in book.segments if s.type is SegmentType.PARAGRAPH]
        assert paragraphs == [c.text for c in chosen]

    def test_segment_ids_match_their_new_coordinates(self, sources):
        """The sample is its own book, not a view onto six others."""
        chosen = corpus.select(sources, "smoke")
        book = corpus.build_sample_book(chosen, sources, "smoke")
        for segment in book.segments:
            assert segment.id == make_segment_id(
                segment.text, segment.chapter_index, segment.position
            )

    def test_positions_are_contiguous_within_a_chapter(self, sources):
        chosen = corpus.select(sources, "smoke")
        book = corpus.build_sample_book(chosen, sources, "smoke")
        by_chapter: dict[int, list[int]] = {}
        for segment in book.segments:
            by_chapter.setdefault(segment.chapter_index, []).append(segment.position)
        for positions in by_chapter.values():
            assert positions == list(range(len(positions)))


class TestManifest:
    def build(self, sources, tier="smoke"):
        chosen = corpus.select(sources, tier)
        book = corpus.build_sample_book(chosen, sources, tier)
        return corpus.manifest_for(tier, chosen, sources, book, "deadbeef")

    def test_carries_no_book_text(self, sources):
        """The books are copyrighted and this file is committed in public."""
        manifest = self.build(sources, "standard")
        rendered = corpus.render_manifest(manifest)

        def walk(node):
            if isinstance(node, dict):
                return [k for key, value in node.items() for k in [key, *walk(value)]]
            if isinstance(node, list):
                return [k for item in node for k in walk(item)]
            return []

        # `title` is permitted at book level as a citation and nowhere else, so
        # assert on the segment records, which are the ones derived from prose.
        for row in manifest["segments"]:
            assert FORBIDDEN_KEYS.isdisjoint(row), row

        # No selected paragraph's text may appear anywhere in the rendered file.
        for candidate in corpus.select(sources, "standard"):
            snippet = " ".join(candidate.text.split()[:6])
            assert snippet not in rendered

        assert "segments" in walk(manifest)

    def test_records_the_coverage_actually_achieved(self, sources):
        manifest = self.build(sources)
        assert manifest["coverage"]["unmet_strata"] == []
        assert sum(manifest["coverage"]["bands"].values()) == corpus.TIERS["smoke"]
        assert manifest["sample"]["paragraphs"] == corpus.TIERS["smoke"]

    def test_names_the_target_languages(self, sources):
        assert self.build(sources)["target_languages"] == list(corpus.TARGET_LANGS)

    def test_renders_stable_line_oriented_json(self, sources):
        manifest = self.build(sources, "standard")
        rendered = corpus.render_manifest(manifest)
        assert rendered == corpus.render_manifest(manifest)
        assert json.loads(rendered) == manifest
        assert rendered.endswith("}\n")
        rows = [line for line in rendered.splitlines() if '"segment_hash"' in line]
        assert len(rows) == manifest["sample"]["paragraphs"]

    def test_lists_only_books_that_contributed(self, sources):
        manifest = self.build(sources)
        assert {b["slug"] for b in manifest["books"]} == {
            c.book_slug for c in corpus.select(sources, "smoke")
        }
        assert sum(b["selected"] for b in manifest["books"]) == corpus.TIERS["smoke"]


class TestVerify:
    def test_a_matching_corpus_reports_nothing(self, sources, monkeypatch):
        manifest = TestManifest().build(sources, "smoke")
        monkeypatch.setattr(corpus, "load_sources", lambda _: sources)
        assert corpus.verify(manifest, "ignored") == []

    def test_changed_text_is_reported(self, sources, monkeypatch):
        manifest = TestManifest().build(sources, "smoke")
        target = manifest["segments"][0]
        mutated = []
        for source in sources:
            clone = SourceBook(
                slug=source.slug,
                title=source.title,
                authors=source.authors,
                source_format=source.source_format,
                book_hash=source.book_hash,
                total_segments=source.total_segments,
            )
            for candidate in source.candidates:
                if (
                    source.slug == target["book"]
                    and candidate.chapter_index == target["chapter_index"]
                    and candidate.position == target["position"]
                ):
                    candidate = Candidate(  # noqa: PLW2901 - deliberate substitution
                        **{**candidate.__dict__, "segment_hash": "changed"}
                    )
                clone.candidates.append(candidate)
            mutated.append(clone)
        monkeypatch.setattr(corpus, "load_sources", lambda _: mutated)
        problems = corpus.verify(manifest, "ignored")
        assert any("text changed" in p for p in problems)

    def test_a_missing_book_is_reported(self, sources, monkeypatch):
        manifest = TestManifest().build(sources, "smoke")
        monkeypatch.setattr(corpus, "load_sources", lambda _: sources[1:])
        problems = corpus.verify(manifest, "ignored")
        assert any("hashes to" in p for p in problems)

    def test_a_shifted_boundary_is_reported(self, sources, monkeypatch):
        """A normalize change that drops a paragraph must not pass silently."""
        manifest = TestManifest().build(sources, "smoke")
        trimmed = []
        for source in sources:
            clone = SourceBook(
                slug=source.slug,
                title=source.title,
                authors=source.authors,
                source_format=source.source_format,
                book_hash=source.book_hash,
                total_segments=source.total_segments,
            )
            clone.candidates = source.candidates[:-5]
            trimmed.append(clone)
        monkeypatch.setattr(corpus, "load_sources", lambda _: trimmed)
        problems = corpus.verify(manifest, "ignored")
        assert any("eligible paragraphs now" in p for p in problems)


class TestRefsDir:
    def test_explicit_wins(self, tmp_path):
        assert corpus.refs_dir(tmp_path) == tmp_path

    def test_environment_is_honoured(self, monkeypatch, tmp_path):
        monkeypatch.setenv(corpus.ENV_REFS_DIR, str(tmp_path))
        assert corpus.refs_dir() == tmp_path

    def test_load_sources_refuses_a_missing_directory(self, tmp_path):
        with pytest.raises(FileNotFoundError):
            corpus.load_sources(tmp_path / "nope")

    def test_main_exits_two_without_reference_books(self, tmp_path):
        assert corpus.main(["build", "--refs", str(tmp_path / "nope")]) == 2


class TestTierShape:
    def test_smoke_is_meaningfully_smaller_than_standard(self):
        assert corpus.TIERS["smoke"] < corpus.TIERS["standard"]

    def test_every_tier_has_both_floors(self):
        assert set(corpus.TIERS) == set(corpus.STRATUM_FLOOR) == set(corpus.BOOK_FLOOR)

    def test_slovenian_is_the_primary_target(self):
        assert corpus.TARGET_LANGS[0] == "sl"
