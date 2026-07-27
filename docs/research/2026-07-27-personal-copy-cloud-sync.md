# Storing and syncing a personal copy — and its LLM translation — in the cloud

**Research note, 27 July 2026. Not legal advice.** This is a sourced map of EU and
Slovenian copyright law as it bears on one product decision. I am not a lawyer and this
document creates no advice relationship. Every operative claim below is tied to a
primary source — a directive article, a statute article, or a numbered paragraph of a
CJEU judgment — so that a Slovenian IP practitioner can check it fast. Several of the
sharpest points are genuinely unsettled, and I have marked them as such rather than
resolving them. Confirm anything you act on with a Slovenian IP lawyer before shipping,
and certainly before offering the feature to anyone other than yourself.

---

## 0. The question, decomposed

"Can I store my own book in the cloud and sync it to my devices so I don't pay to
translate twice?" is not one legal question. It is four acts, each with a different legal
basis, and they do not stand or fall together:

| # | Act | Right engaged | Governing provision |
|---|-----|---------------|---------------------|
| A | Copying the source file (extract, normalise, store segments) | Reproduction | InfoSoc Art. 2 → exception in Art. 5(2)(b) → **ZASP Art. 50** |
| B | Producing the Slovenian translation | **Adaptation / translation** — *not harmonised at EU level* | Berne Art. 8, **ZASP Art. 33** → exception in **ZASP Art. 53(1)1** |
| C | Sending segments to a third-party LLM API | Reproduction + transfer to a third party | ZASP Art. 50; contract with the provider |
| D | Storing in the cloud and syncing to own devices | Reproduction ("any medium") — and possibly communication to the public | **Austro-Mechana C-433/20**; **VCAST C-265/16**; DSM Art. 2(6) |

The headline result of this research is counter-intuitive and worth stating up front:

> **D — the cloud storage and sync layer, the part that felt legally risky — is the
> better-supported half. B — making the translation — has an express Slovenian
> exception that covers it. The binding constraint is A: ZASP Art. 50(4) prohibits
> private reproduction of a written work "in the scope of an entire book". That
> constraint already bites the Phase 1 CLI as it exists today. It is not created by the
> cloud plan.**

Everything below assumes the file is **DRM-free and lawfully acquired**. Circumvention of
technological measures is a separate prohibition (ZASP Arts. 166.a–166.c, InfoSoc Art. 6)
and Berilo's non-goals already exclude it (`docs/project_spec.md` §7). That exclusion is
doing real legal work, not just signalling.

---

## 1. What is settled

### 1.1 The private-copying exception is optional, narrow, and strictly construed

InfoSoc Directive 2001/29/EC Art. 5(2)(b) lets Member States except

> "reproductions on any medium made by a natural person for private use and for ends that
> are neither directly nor indirectly commercial, on condition that the rightholders
> receive fair compensation…"

Four cumulative conditions: **natural person**, **private use**, **non-commercial**,
**fair compensation**. Art. 5(5) adds the three-step test — special cases, no conflict
with normal exploitation, no unreasonable prejudice to the rightholder.

The Court construes it strictly, as a derogation from a general principle
(*VCAST*, C-265/16, ¶32, citing *ACI Adam*, C-435/12, ¶22). It is an exception to the
**reproduction right in Art. 2 only**. It does not touch Art. 3 (communication to the
public), and it does not touch adaptation.

### 1.2 The source must be lawful

*ACI Adam* (C-435/12, 10 April 2014): Art. 5(2)(b) "must be interpreted as not covering
the case of private copies made from an unlawful source" (¶41). National law that fails to
distinguish lawful from pirated sources "cannot be tolerated" (¶37), because tolerating
copies from unlawful sources "would encourage the circulation of counterfeited or pirated
works" and so conflict with normal exploitation (¶39).

*ACI Adam* ¶31 is the sentence to remember: the exception stops the rightholder relying on
the reproduction right against the private copier, but it does **not** require the
rightholder "to tolerate infringements of his rights which may accompany the making of
private copies." The exception is a shield for one act, not for everything around it.

**Consequence for Berilo:** "the user supplies files they own" is load-bearing, and
"own" has to mean lawfully acquired. A book the user bought is a lawful source. A file
from a shadow library is not, and no amount of client-side encryption fixes that.

### 1.3 You need not own the equipment, and a third party may do the copying for you

*Padawan* (C-467/08, 21 October 2010) ¶46, ¶48: private users may "provide copying
services for them" via third parties; the third party's provision of equipment or copying
services "is the factual precondition for natural persons to obtain private copies."

*Copydan Båndkopi* (C-463/12, 5 March 2015) ¶86, ¶89: Art. 5(2)(b) "does not specify the
characteristics of the devices", and whether the device "must belong to that person or
whether it may belong to a third party falls outside the scope of Article 5(2)(b)."

**Consequence:** the mere fact that a server belongs to Berilo rather than to Niko is not,
by itself, a problem.

### 1.4 Cloud storage of your own copy *is* private copying — this is directly settled

*Austro-Mechana v Strato* (C-433/20, 24 March 2022) is the single most useful authority
here, and it is squarely on the facts.

- Uploading a work to personal cloud storage is a reproduction (¶17–18).
- "any medium" is an autonomous EU concept covering "all media on which a protected work
  may be reproduced, **including servers such as those used in cloud computing**" (¶21).
- Technological neutrality requires it: excluding cloud would let copyright exceptions
  "become outdated or obsolete as a result of technological developments" (¶27–28).
- **¶29 is the operative sentence:** "it is not necessary, in terms of functionality, to
  make a distinction… according to whether the reproduction… is carried out on a server on
  which storage space has been made available to a user by the provider of a cloud
  computing service or whether such a reproduction is made on a physical recording medium
  belonging to that user."

Operative ruling: "reproductions on any medium" covers "the saving, for private purposes,
of copies of works protected by copyright on a server on which storage space is made
available to a user by the provider of a cloud computing service" (¶33).

### 1.5 VCAST is distinguishable — and the Court itself distinguished it

*VCAST* (C-265/16, 29 November 2017) held that InfoSoc "precludes national legislation
which permits a commercial undertaking to provide private individuals with a cloud service
for the remote recording of private copies… **by actively involving itself in the
recording**, without the rightholder's consent."

Read the ratio, not the headline. The reason VCAST lost is stated at ¶37–38:

> "the provider of that service **does not merely organise the reproduction, but also
> provides access to the programmes** … Thus, the service at issue has a **dual
> functionality**, consisting in ensuring both the reproduction and the making available
> of the works."

VCAST supplied the content. It captured broadcasts with its own antennas and handed users
works they did not have. That is why Art. 3 was engaged (¶46–49: different technical means,
different publics) and why the copying could not be sheltered (¶52).

*Austro-Mechana* ¶31 makes the distinction explicit, rejecting the Commission's attempt to
extend VCAST: VCAST "concerned a service with a dual functionality, namely not only
reproduction in the cloud, but also, simultaneously or almost simultaneously,
communication to the public." And ¶32: sharing by the *user* "would constitute an act of
exploitation that is distinct from the reproduction act."

**Consequence, and it is the design pivot of this whole document:** Berilo does not supply
content. The user supplies the file. Berilo has *single* functionality — storage — for
as long as no path exists by which one user's content reaches another. The moment such a
path exists, Berilo acquires VCAST's dual functionality and the analysis inverts.

### 1.6 The DSM Directive's Art. 17 does not apply to a personal-sync service

Directive (EU) 2019/790 Art. 2(6) defines an "online content-sharing service provider" as
one whose main purpose is "to store and give **the public** access to a large amount of
copyright-protected works… uploaded by its users, which it organises and promotes for
profit-making purposes." The second subparagraph then excludes, expressly:

> "…business-to-business cloud services and **cloud services that allow users to upload
> content for their own use**, are not 'online content-sharing service providers' within
> the meaning of this Directive."

That is Berilo's sync service, described in the Directive's own words. Art. 17's licensing
and best-efforts-filtering regime does not attach. The applicable liability framing is the
ordinary hosting safe harbour: **Regulation (EU) 2022/2065 (DSA) Art. 6** (no liability for
stored information absent actual knowledge, expeditious removal on obtaining it) and
**Art. 8** (no general monitoring obligation).

Note that DSA Art. 6(2) withdraws the safe harbour where "the recipient of the service is
acting under the authority or the control of the provider." A service that itself performs
the copying on the user's behalf edges toward that. A service that stores what the user
uploads does not.

### 1.7 Making a translation is an act of adaptation — and EU law does not harmonise it

This is the point the brief flagged as sharpest, and it resolves more cleanly than
expected, but only because of a *national* provision.

**Berne Convention Art. 8** (Paris 1971): authors "shall enjoy the exclusive right of
**making and of authorizing the translation** of their works." Note "making" — the
translation right is engaged by the act of translating, not only by publishing the result.

**Berne Art. 9(2)** — the three-step test — applies by its terms only to *reproduction*:
"It shall be a matter for legislation in the countries of the Union to permit **the
reproduction** of such works in certain special cases…" There is no Art. 9(2) equivalent
attached to Art. 8. Berne provides no general exception mechanism for the translation
right.

**Berne Art. 12**: exclusive right of authorising "adaptations, arrangements and other
alterations."

**InfoSoc harmonises none of this.** I searched the full text of Directive 2001/29/EC: it
contains no adaptation right and no translation right. The word "adaptation" appears once,
in the heading of Art. 11, "Technical adaptations", which is about amending earlier
directives. The harmonised rights are reproduction (Art. 2), communication and making
available (Art. 3), and distribution (Art. 4). Adaptation of literary works is left
entirely to national law. (Contrast Directive 2009/24/EC Art. 4(1)(b), which *does*
expressly cover translation and adaptation — but of computer programs, not books.)

**So Art. 5(2)(b) cannot authorise the translation.** It is an exception to the
reproduction right and nothing else. Anyone reasoning "private copying covers my private
translation" has skipped a step. The legal basis for the translation is separate from the
legal basis for the copy, exactly as the brief suspected.

### 1.8 Slovenia supplies the missing basis: ZASP Art. 53(1)1

**ZASP Art. 33(1)**: "Pravica predelave je izključna pravica, da se neko prvotno delo
**prevede**, odrsko priredi, glasbeno aranžira, spremeni ali kako drugače predela."
The right of adaptation is the exclusive right to translate. Art. 22(3) classes it as
"uporaba dela v spremenjeni obliki". Art. 33(3): the author retains the exclusive right to
use the work in adapted form — "**če ni s tem zakonom ali s pogodbo drugače določeno**"
(unless otherwise provided by this Act or by contract).

The Act does provide otherwise. **ZASP Art. 53 — "Proste predelave" (free adaptations):**

> "(1) Predelava objavljenega dela je prosta:
> 1. **če gre za privatno ali drugo lastno predelavo, ki ni namenjena in ni dostopna
>    javnosti**;
> 2. če gre za predelavo v parodijo, pastiš ali karikaturo…
> 3. če gre za predelavo v zvezi z dovoljeno uporabo, ki jo zahteva namen te uporabe;
> 4. če gre za predelavo v zvezi z dovoljeno uporabo, pa je avtorjevo nasprotovanje
>    predelavi v nasprotju z načelom vestnosti in poštenja."

Adaptation of a **published** work is free where it is a **private or other personal
adaptation that is neither intended for nor accessible to the public**.

Translating a published book for yourself, where the result never becomes accessible to
the public, falls within Art. 53(1)1 on its face. This is a national exception that EU law
does not constrain, because EU law does not harmonise the right it limits.

It is subject to the general rule in **ZASP Art. 46**: the scope of such use is limited by
the purpose to be achieved, must accord with good practice (*dobri običaji*), must not
conflict with normal exploitation of the work, and must not unreasonably prejudice the
author's legitimate interests. That is Slovenia's three-step test plus a good-faith
overlay.

Two conditions in Art. 53(1)1 are doing real work and both are design-relevant:

- **"objavljenega dela"** — published work. Fine for commercially published books.
- **"ni namenjena in ni dostopna javnosti"** — not intended for *and* not accessible to
  the public. Conjunctive. Intent alone does not save an adaptation that is in fact
  accessible. A translation sitting in a store that other users could reach fails this
  limb even if nobody ever reaches it.

*ZASP-I* (Ur. l. RS 130/2022), which implemented the DSM Directive, amended Art. 53 only by
adding "pastiš" to point 2. Point 1 is untouched. It also left Art. 50 substantively
untouched, and added text-and-data-mining exceptions at new Arts. 57.a–57.d.

### 1.9 The binding constraint: ZASP Art. 50(4) bars copying an entire book

**ZASP Art. 50 — "Privatno in drugo lastno reproduciranje":**

> "(1) Ob upoštevanju 37. člena tega zakona je reproduciranje že objavljenega dela prosto,
> če je izvršeno **v največ treh primerkih** in če so izpolnjeni pogoji iz drugega ali
> tretjega odstavka tega člena.
>
> (2) Fizična oseba lahko prosto reproducira delo … 2. **na katerem koli drugem nosilcu,
> če to stori za privatno uporabo, če primerki niso izročeni ali priobčeni v javnosti in
> če pri tem nima namena dosegati neposredne ali posredne gospodarske koristi.**
>
> (4) **Reproduciranje po prejšnjih odstavkih tega člena ni dovoljeno glede pisanih del v
> obsegu celotne knjige**, grafičnih izdaj glasbenih del, elektronskih baz podatkov in
> računalniških programov … **če ni s tem zakonom ali s pogodbo drugače določeno**.
>
> (5) Ne glede na prejšnji odstavek je … prosto: 1. reproducirati pisano delo v obsegu
> celotne knjige, **če je njena naklada izčrpana že najmanj dve leti**…"

Three hard numbers fall out, and all three land on this product:

1. **Whole books are excluded (¶4).** Slovenia is *stricter* than the InfoSoc floor here.
   Art. 5(2)(b) is optional and Member States may implement it narrowly; Slovenia did.
   Berilo's pipeline reproduces the entire book by construction — extraction, segment
   store, cache, translated EPUB. On a literal reading of Art. 50(4), that is outside the
   private-copying exception.
2. **Maximum three copies (¶1).** The stated goal is phone + Boox tablet + laptop, plus a
   cloud copy. That is four, before counting the segment cache and the source file.
3. **Escape hatches exist.** Art. 50(5)1: whole-book reproduction is free if the print run
   has been exhausted for at least two years. And Art. 50(4) yields to contract —
   "če ni … s pogodbo drugače določeno" — so an ebook licence that permits personal copies
   or format-shifting changes the answer for that book.

**This is the finding that matters most, and it is not about the cloud at all.** The
existing Phase 1 CLI, running locally with no server, already sits on Art. 50(4). Adding
cloud sync does not create this exposure; it inherits it.

### 1.10 Fair compensation in Slovenia does not obviously reach this case

**ZASP Art. 37(1)** grants a right to fair compensation for "**tonsko ali vizualno
snemanje in za fotokopiranje**" — sound or visual recording and photocopying — under the
private-use conditions of Art. 50. Art. 37(2) attaches the levy to recording devices and
blank audio/video media.

Digital reproduction of a *text* onto "any other medium" under Art. 50(2)2 has no
corresponding levy. After *Austro-Mechana* ¶38–40 (a Member State implementing 5(2)(b)
"must ensure… the effective recovery of the fair compensation"), Slovenia's coverage looks
incomplete. That is a defect in the *State's* transposition, not a liability sitting on a
private user, and I would not build anything on it. Flagging it because a lawyer may read
the gap differently — possibly as an argument that Art. 50(2)2 was never intended to reach
this class of copying at all.

### 1.11 DRM: no self-help route around any of this

ZASP Art. 166.c(1) obliges a rightholder using technological measures to enable
beneficiaries with lawful access to exercise certain exceptions; Art. 166.c(3) as amended
by ZASP-I lists them, and **point 4 is Art. 50, private and other own reproduction**.

Two limits kill this as a route:

- **Art. 53 (free adaptations) is not on the list.** There is no enforceable claim to have
  a technological measure lifted so you can translate.
- Art. 166.c(4) disapplies the mechanism for works made available under agreed contractual
  terms on an on-demand basis — which is precisely how commercial ebooks are sold
  (InfoSoc Art. 6(4), fourth subparagraph). For a Kindle or Kobo file, the mechanism is
  very likely unavailable.

---

## 2. What is genuinely unsettled or fact-dependent

I have not found authority resolving these. Do not let anyone, including me, tell you they
are clear.

**(a) Does Art. 53(1)1 carry with it the reproduction that translation requires?**
This is the central unresolved question. Art. 53 permits the *adaptation*. Making a
translation necessarily entails reproducing the source (ZASP Art. 23(2) expressly includes
"shranitev v elektronski obliki"). If that reproduction is judged under Art. 50, the
whole-book bar in Art. 50(4) applies and the permission in Art. 53 is largely hollow for
books — you may translate a book you cannot lawfully copy. Two counter-arguments exist and
neither is tested: Art. 50(4)'s own proviso "če ni **s tem zakonom** … drugače določeno"
could be read to let Art. 53 displace it; and Art. 53(1)3 permits adaptation "in
connection with permitted use, required by the purpose of that use", which may work in
reverse. This is the first question to pay a lawyer for.

**(b) What counts toward the three-copy limit in Art. 50(1)?**
Whether transient working files, the segment cache, a derived EPUB, and a cloud replica
each count as a *primerek*, and whether the count is per-act or concurrent, I could not
establish. InfoSoc Art. 5(1) shelters transient and incidental copies with no independent
economic significance, which probably excludes pipeline temporaries, but "probably" is the
honest word. If the count is strict and concurrent, the stated three-device goal plus a
cloud copy is already at or over the line.

**(c) Is the translated EPUB a fresh "entire book" for Art. 50(4)?**
The translation is an independent work in which the original author retains rights
(Art. 33(3)). Whether reproducing *it* re-triggers the whole-book bar as against the
original author's rights is not something I found addressed.

**(d) Is Slovenia's Art. 53(1)1 compatible with Berne Art. 8?**
Berne grants an exclusive right of *making* translations with no Art. 9(2)-style exception
mechanism. A national exception for private translation sits awkwardly with that. In
practice this is a State-to-State compliance question with no private cause of action, and
the minor-exceptions doctrine is often invoked for de minimis uses. But it means Art. 53(1)1
is not as unassailable as its plain text suggests, and it would be unwise to build a
*business* on it, as opposed to a personal tool.

**(e) Sending the book to a third-party LLM API.**
This already happens today and is the least examined act in the whole design. Transmitting
segments to OpenAI or Anthropic is a reproduction plus a transfer outside the user's
sphere. It is arguably within Art. 50(2)2 as copying "for private use" through a
third-party service (*Padawan* ¶46, ¶48; *Copydan* ¶89 — third-party equipment is fine).
But "primerki niso izročeni" — copies are not handed over — is a condition of Art. 50(2)2,
and handing every segment of a book to a commercial API is at least arguably an *izročitev*.
The provider's terms matter here: whether it retains, logs, or trains on the content. That
is contract and data governance as much as copyright.

**(f) Whether "private use" survives a service with an account and a price.**
Art. 50(2)2 requires no intention of direct or indirect economic benefit — assessed on the
*user*, who has none. But a paid service that exists to make private copies cheaper invites
the argument that the *provider* is commercially exploiting the exception. *Padawan* ¶45–48
treats the provider as a permissible conduit, and *Austro-Mechana* ¶43 confirms the user
funds the compensation. So the better view is that a paid service is fine. It is not
risk-free, and it is materially riskier than a tool Niko runs for himself.

**(g) Cross-border.** The user is in Slovenia; a Vercel or Supabase region may not be.
Reproduction is territorial and *Austro-Mechana* ¶48 notes the difficulties dematerialised
services create. Which national private-copying regime governs a copy on a Frankfurt server
made by a Ljubljana user is not settled by anything I found.

---

## 3. Design constraints that follow

Mapped to Berilo's actual architecture. These are engineering consequences of §1, not
legal advice.

### 3.1 Do — these keep the service on the *Austro-Mechana* side of the line

1. **Per-user isolation of stored content, enforced at the database layer.** Every row
   carrying book content or translated text is owned by exactly one user, with Supabase
   RLS on the table. This is the single most important constraint in this document.
2. **User-initiated upload only.** The user's device uploads a file the user already
   possesses. The server never fetches, never retrieves, never acquires content on the
   user's behalf. This is the *VCAST* ¶37 line: organise the reproduction, never provide
   access to the work.
3. **Client-side encryption with a key the server never holds.** The provider cannot read
   the content. This does three things at once: it makes cross-user leakage structurally
   impossible rather than merely prohibited; it strengthens the argument that the copy
   remains the user's private copy in the sense of Art. 50(2)2 rather than something
   "izročen" to a third party; and it forecloses the deduplication temptation in §3.2.
4. **No sharing surface for book content, of any kind.** No public links, no "send to a
   friend", no family plans, no support-side content access. Note the existing spec
   (§6.1) already caps shared passages at 500 characters with attribution — that is a
   citation-length excerpt under ZASP Art. 51 (*Citati*) and is a different, defensible
   thing. Keep the wall between "share a quotation" and "share a book" absolute.
5. **Keep the reading page metadata-only.** Title, author, cover, language pair, rating.
   Already the spec's position. It is also the legally correct one.
6. **Record provenance at import.** Ask the user to affirm lawful acquisition and store
   the affirmation. *ACI Adam* ¶41 makes lawful source a condition, and a service with no
   provenance story looks indifferent to it.
7. **Device count as a product limit, not just a UX default.** Given Art. 50(1)'s
   three-copy rule, cap active devices per user and make the cap visible. Three devices
   plus a cloud replica is already arguable; ten is not.
8. **Preserve the local-only path.** The app must stay fully functional with sync off —
   already the spec's position (§6). It also means the legally novel layer is opt-in and
   severable.

### 3.2 Do not

1. **Never share the translation cache across users.** The cache primary key is today
   `(book_hash, segment_hash, model, lang, prompt_version)` — content-addressed, with no
   user column. That schema is correct on one machine and dangerous on a server. Hosted
   as-is, two users importing the same ISBN collide on `book_hash` and the second is
   served the first's translated text.

   **That is not private copying. It is reproduction plus making available to the public
   under InfoSoc Art. 3(1) / ZASP Art. 32.a**, and it is the exact fact pattern of *Tom
   Kabinet* (C-263/18), where supplying ebooks by download to registered users was held to
   be communication to the public with no digital exhaustion — cited approvingly in
   *Austro-Mechana* ¶31. It also hands the service *VCAST*'s dual functionality: it would
   no longer merely organise reproduction, it would provide access to the work (*VCAST*
   ¶37–38). And it breaks Art. 53(1)1 directly, because the adaptation becomes "dostopna
   javnosti".

   **Per-user isolation is the line that must not be crossed.** If any hosted cache is
   built, the user ID belongs *in the primary key*, not in a WHERE clause that a future
   refactor can drop. Deduplication across users is the same defect wearing a
   cost-optimisation hat — and it is precisely the shape the stated goal ("don't pay to
   translate twice") will keep pulling toward. The goal is legitimate *within one user's
   account*, across their own devices. It is not legitimate across accounts.

2. **No server-side translation initiated by the service.** Keep translation on the user's
   machine or under the user's explicit command with their own API key — the existing BYO-key
   invariant. A server that translates on its own initiative starts to look like an entity
   "actively involving itself" (*VCAST*, operative ruling).

3. **No content-addressed storage keyed on book hash alone.** Same defect as (1) at the
   blob layer. Namespace every object by user.

4. **No DRM circumvention, no acquisition helpers, no "find this book" feature.** Already
   a stated non-goal. §1.11 shows there is no lawful self-help route, so this non-goal has
   to hold even when it is inconvenient.

5. **Do not let the sync service acquire editorial control over stored content.** DSA
   Art. 6(2) removes the safe harbour where the recipient acts "under the authority or the
   control of the provider". Store what the user sends; do not curate it.

### 3.3 The constraint that no design choice fixes

None of the above cures **ZASP Art. 50(4)**. Whole-book private reproduction is outside the
Slovenian exception regardless of where the bytes sit. The available routes are all
outside engineering:

- rely on Art. 50(5)1 where the print run has been exhausted two years or more;
- rely on the source licence where it permits personal copies (Art. 50(4) yields to
  contract);
- rely on the argument in §2(a) that Art. 53 displaces Art. 50(4) for translation — untested;
- or accept that this is a personal tool operating in a grey zone, which is a very
  different risk posture from a product with paying users.

Personal use by one person is, realistically, unenforced. A published service is not. The
gap between those two is where the actual decision lies, and it is a business decision, not
a technical one.

### 3.4 Adjacent, not researched here

Cloud sync of notes, highlights and reading positions is personal data under the GDPR, and
holding encrypted book files does not exempt the surrounding metadata. Out of scope for
this note; worth its own pass before Phase 3 ships.

---

## 4. Questions to put to a Slovenian IP lawyer

Ordered by how much the answer changes what gets built. Each is phrased to be answerable.

1. **Does ZASP Art. 53(1)1 (privatna predelava) permit a natural person to produce a
   machine translation of an entire lawfully acquired published book for personal use —
   and does that permission carry with it the reproduction of the whole book that the
   translation necessarily requires, notwithstanding the whole-book exclusion in
   Art. 50(4)?** If not, is there any lawful route for whole-book private translation
   outside Art. 50(5)1 (naklada izčrpana dve leti) and an express contractual licence?

2. **How is the three-copy limit in ZASP Art. 50(1) counted** for a digital pipeline —
   do intermediate artefacts (extracted segment store, translation cache, generated EPUB)
   and a cloud replica each count as a *primerek*, and is the limit concurrent or
   cumulative? Concretely: source file + cloud copy + phone + tablet + laptop — how many
   *primerki* is that?

3. **Does transmitting a book's text in segments to a third-party LLM API for translation
   satisfy the condition in Art. 50(2)2 that "primerki niso izročeni"** — and does the
   answer turn on the provider's retention, logging and training terms? Would a
   zero-retention contractual commitment from the API provider change it?

4. **Would a paid service that stores a user's own encrypted book files and syncs them to
   that user's own devices — with no cross-user access, no deduplication, no sharing
   feature, and no server-side translation — be treated as within the private-copying
   framework** on the reasoning of *Austro-Mechana* (C-433/20), or would the provider risk
   being characterised as "actively involving itself" under *VCAST* (C-265/16)? Which
   specific features would tip it?

5. **Does client-side encryption where the provider holds no key materially change the
   analysis** under Art. 50(2)2, or is it legally neutral and merely good security hygiene?

6. **Confirm that a service of this kind is excluded from DSM Art. 17 / ZASP Arts. 163.a
   et seq.** by the Art. 2(6) carve-out for "cloud services that allow users to upload
   content for their own use", and that the applicable liability regime is DSA Arts. 6
   and 8.

7. **If Berilo were offered commercially, what obligations arise on the fair-compensation
   side?** ZASP Art. 37 appears to cover only sound/visual recording and photocopying.
   Post-*Austro-Mechana*, is there exposure to a SAZOR or comparable claim in respect of
   digital text copying, and does the answer differ for a provider established in Slovenia
   versus hosting in another Member State?

8. **What does the exposure actually look like in practice** — is the realistic risk a
   civil claim by a publisher or collecting society, and what is the practical difference
   in posture between (a) Niko's personal use, (b) a free open-source tool users run
   themselves with their own API keys, and (c) a paid hosted service? This is the question
   that decides the product, and it is a risk question, not a doctrinal one.

9. **Do the standard consumer ebook licences in this market (Amazon, Kobo, Google Play
   Books, Slovenian retailers) contain terms that permit or forbid personal format-shifting
   and translation** — and, given Art. 50(4)'s "če ni … s pogodbo drugače določeno", can a
   licence *expand* the private-copying permission as well as restrict it?

---

## 5. Bibliography

### Treaties

- Berne Convention for the Protection of Literary and Artistic Works (Paris Act, 1971),
  Arts. 8 (translation), 9(1)–(2) (reproduction; three-step test), 12 (adaptations).
  https://www.wipo.int/wipolex/en/text/283698 ·
  Art. 8: https://www.law.cornell.edu/treaties/berne/8.html ·
  Art. 9: https://www.law.cornell.edu/treaties/berne/9.html

### EU legislation

- Directive 2001/29/EC (InfoSoc), Arts. 2, 3, 5(2)(b), 5(5), 6(4).
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32001L0029
- Directive (EU) 2019/790 (DSM), Arts. 2(6), 3, 4, 17.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32019L0790
- Regulation (EU) 2022/2065 (Digital Services Act), Arts. 6, 8.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32022R2065
- Directive 2009/24/EC (Computer Programs), Arts. 4(1)(b), 5(1) — cited for contrast only.

### CJEU judgments

- C-467/08 *Padawan v SGAE*, 21 Oct 2010, ECLI:EU:C:2010:620 — ¶¶44–48, 52–54, 59.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62008CJ0467
- C-435/12 *ACI Adam BV and Others*, 10 Apr 2014, ECLI:EU:C:2014:254 — ¶¶22, 31, 37–39, 41.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62012CJ0435
- C-463/12 *Copydan Båndkopi*, 5 Mar 2015, ECLI:EU:C:2015:144 — ¶¶85–89, 91.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62012CJ0463
- C-265/16 *VCAST v RTI*, 29 Nov 2017, ECLI:EU:C:2017:913 — ¶¶29–33, 37–39, 46–54.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62016CJ0265
- C-263/18 *Nederlands Uitgeversverbond and Groep Algemene Uitgevers v Tom Kabinet*,
  19 Dec 2019, ECLI:EU:C:2019:1111 — digital exhaustion; ebook supply as communication to
  the public. https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62018CJ0263
- C-433/20 *Austro-Mechana v Strato AG*, 24 Mar 2022, ECLI:EU:C:2022:217 — ¶¶16–33, 38–48.
  https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62020CJ0433

### Slovenian legislation

- Zakon o avtorski in sorodnih pravicah (ZASP), Ur. l. RS 16/07 – UPB, 68/08, 110/13,
  56/15, 63/16, 59/19, 130/22. https://pisrs.si/Pis.web/pregledPredpisa?id=ZAKO403
  - Art. 22 — Materialne avtorske pravice (¶3: uporaba v spremenjeni obliki)
  - Art. 23 — Pravica reproduciranja
  - Art. 33 — Pravica predelave
  - Art. 37 — Pravica do nadomestila
  - Art. 46 — Splošno pravilo (three-step test)
  - **Art. 50 — Privatno in drugo lastno reproduciranje** (¶1 three copies; ¶2(2) any
    medium; ¶4 whole-book exclusion; ¶5(1) out-of-print exception)
  - Art. 51 — Citati
  - **Art. 53 — Proste predelave** (¶1(1) private adaptation)
  - Art. 166.c — Uveljavljanje vsebinskih omejitev pravic
- Zakon o spremembah in dopolnitvah ZASP (**ZASP-I**), Ur. l. RS 130/2022 — DSM
  implementation; amends Art. 53(2) (adds "pastiš"), Art. 166.c(3), adds Arts. 57.a–57.d
  (TDM). https://www.uradni-list.si/glasilo-uradni-list-rs/vsebina/2022-01-3087

### Secondary commentary (used for orientation; all propositions verified against primary text)

- B. J. Jütte, "The Court of Justice Rules on the Private Copying Exception on Cloud
  Servers: *Austro-Mechana v Strato AG* (C-433/20)", EU Law Live, 2022.
  https://eulawlive.com/analysis-the-court-of-justice-rules-on-the-private-copying-exception-on-cloud-servers-austro-mechana-v-strato-ag-c-433-20-by-bernd-justin-jutte/
- E. Rosati, "CJEU rules that private copying also applies in the cloud and warns against
  thinking that everything is communication to the public", The IPKat, 2022.
  https://ipkitten.blogspot.com/2022/04/cjeu-rules-that-private-copying-also.html
- E. Rosati, "The VCAST decision: how to turn a private copying case into a case about
  communication/making available to the public", The IPKat, 2017.
  https://ipkitten.blogspot.com/2017/11/the-vcast-decision-how-to-turn-private.html
- C. Sganga, "Exhaustion, Distribution and Communication to the Public — The CJEU's
  Decision C-263/18 *Tom Kabinet* on E-Books and Beyond", GRUR International 69(5), 2020.
  https://academic.oup.com/grurint/article/69/5/489/5854748
- Dechert LLP, "CJEU rules on private copying exception to storage in the cloud", 2022.
  https://www.dechert.com/knowledge/onpoint/2022/4/cjeu-rules-on-private-copying-exception-to-storage-in-the-cloud.html

### Method and limits

Judgment and directive texts were retrieved as primary sources from the EU Publications
Office CELLAR service (`https://publications.europa.eu/resource/celex/<CELEX>`) and read in
full for the cited paragraphs; EUR-Lex web URLs are given above for human reference.
Slovenian statutory text was read from a consolidated public reproduction of ZASP-NPB11
and cross-checked against the ZASP-I amending act as published in Uradni list RS 130/2022.

Two limits worth stating. First, I found **no Slovenian case law** applying Art. 53(1)1 or
Art. 50(4) to digital or machine translation; the readings in §1.8 and §1.9 rest on
statutory text, not on how Slovenian courts have in fact applied it, and a practitioner
with access to national commentary (Trampuž/Oman/Zupančič on ZASP) may know of authority
that changes them. Second, no CJEU or national authority addresses machine translation of a
whole book for private use specifically. The framework is assembled from adjacent
authorities. That assembly is the most fragile part of this document, and §2(a) is where it
is thinnest.
