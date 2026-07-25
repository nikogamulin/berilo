# Kako je Berilo nastalo z agenti

🇬🇧 **English:** [how_it_was_built.md](how_it_was_built.md)

Berilo je prevajalnik v ukazni vrstici, bralnik za Android in merilni pripomoček
— več kot 100 commitov, 419 testov, 5 prevedenih knjig, v dveh dneh, za €6,68
porabe za API. Skoraj nič od tega ni bilo natipkano ročno. Zgradila ga je nadzorovana
večagentna zanka v okolju [Claude Code](https://claude.com/claude-code).

Ta dokument je metoda, ne predstavitev. Za vsako trditvijo spodaj stoji artefakt
v tem repozitoriju.

---

## 1. Problem, ki ga je moral proces rešiti

Agenti hitro napišejo verjetno videti kodo. Prav verjetnost je težava. Prevajalni
cevovod odpove tiho: EPUB se sestavi, testi so zeleni, izhod je tekoč — pomen pa
je napačen, ali manjka poglavje, ali pa je bila »izboljšava«, ki si jo pravkar
preizkusil z A/B testom, postrežena iz predpomnilnika in model sploh ni bil
poklican.

Zato je proces zgrajen okoli enega pravila: **nič ne šteje, dokler ni izmerjeno s
številko, ki je obstajala, preden se je delo začelo.**

Ta številka je rubrika. [`docs/rubric.md`](rubric.md) definira T (kakovost
prevoda), R (bralska izkušnja), S (sinhronizacija) in D (zdravje procesa), vsako
s postopkom ocenjevanja, utežmi in pragom. Rubrika T se oceni tako, da se pri
fiksnem semenu vzorči 40 parov izvirnik/prevod, oceni z jezikovnim modelom in
bootstrapira 95 % interval zaupanja. Ni stvar občutka; je ponovljiva in stane
približno €0,15.

## 2. Oblika: en Supervisor, pet specializiranih agentov

<p align="center">
  <img src="../assets/social/berilo-orchestrate-agents.png" alt="Cevovod orchestrate: Supervisor v središču ima specifikacijo, zgodbe, valove, združevanja, stroške in skupno stanje; okoli njega plan-critic pred gradnjo, task-implementer v ločenih worktreejih, impl-reviewer pred združitvijo in defect-investigator za forenziko" width="760">
</p>

Glavni pogovor je **Supervisor**. Ta ne implementira. Razstavlja, delegira,
preverja in združuje. Okoli njega so ozko specializirani agenti, definirani v
[`.claude/agents/`](../.claude/agents/):

| Agent | Model | Naloga | Ne sme |
|-------|-------|--------|--------|
| `plan-critic` | Opus | Napade specifikacijo, *preden* nastanejo zgodbe: nepreverljiva merila, skrite odvisnosti, nerealni stroški, nasprotja z zabeleženimi ugotovitvami | Predlagati načrta |
| `task-implementer` | Sonnet (Opus za težke zgodbe) | Implementira natanko ENO zgodbo v ločenem git worktreeju, testi zeleni z mockanimi klici modela | Trošiti API, se dotikati naprave, pisati skupnega stanja |
| `impl-reviewer` | Opus | Adversarni pregled diffa pred združitvijo: širjenje obsega, poštenost testov, uhajanje skrivnosti, varnost stroškov | Karkoli popravljati |
| `defect-investigator` | Opus | Forenzika ene same napake, samo branje; vrne razvrstitev in osnutek zgodbe | Spreminjati kode |
| `Explore` skavti | Sonnet | Vzporedno branje: kartiranje kode, analiza vpliva | Pisati |

Trije nespremenljivi zakoni poskrbijo, da to deluje, namesto da bi se sprevrglo v
roj nasprotujočih si sprememb:

1. **Skupno stanje ima enega samega pisca.** Samo Supervisor piše kljukice v
   načrtu, register ugotovitev, dnevnik in datoteko s kanoničnimi pravili. Agenti
   predlagajo; Supervisor zapiše.
2. **Serializirani viri se nikoli ne delegirajo.** Plačljivi teki celih knjig,
   naprava Boox in vsak `git push` pripadajo Supervisorju. Agent ne more porabiti
   denarja ali pokvariti strojne opreme.
3. **Vzporedni implementerji samo na nepokrivajočih se množicah datotek, največ
   3 hkrati.** Vsaka zgodba vnaprej napove, katerih datotek se bo dotaknila;
   Supervisor razporedi valove, ki se ne zaletijo. Vsak implementer dela v svojem
   `git worktree`.

Sam cevovod — prevzem → specifikacija → kritik → zgodbe → valovi → pregled →
pristanek → ocena — je zapisan kot izvedljiv skill v
[`.claude/skills/orchestrate/SKILL.md`](../.claude/skills/orchestrate/SKILL.md),
z ločenim pragom preverjanja v
[`.claude/skills/verify-implementation/SKILL.md`](../.claude/skills/verify-implementation/SKILL.md).
Agent na začetku vsake naloge prebere svoja lastna navodila za uporabo.

## 3. Zanka, ki dejansko proizvaja napredek

<p align="center">
  <img src="../assets/social/berilo-concrete-build-loop.png" alt="Konkretna razvojna zanka s štirimi postajami in izmerjenimi rezultati petih prevedenih knjig" width="760">
</p>

Ena iteracija:

```
hipoteza  →  implementacija (v worktreeju)  →  adversarni pregled  →  združitev
    ↑                                                                     ↓
nova hipoteza  ←  forenzika napake  ←  ocena rubrike  ←  izvedba vrstice Verify
```

Poštenost zagotavljata dva mehanizma:

**Vsaka zgodba nosi vrstico Verify** — izvedljiv ukaz ali izmerjen prag, zapisan
*pred* implementacijo. Ne »testi so zeleni«, ampak na primer:

> `berilo eval "…Revenge of Geography.sl.epub" --sample 30 --seed 42 --dump`
> vsebuje **nič** vzorcev, katerih prevod je bajt za bajt enak angleškemu
> izvirniku.

Zgodbo se sme odkljukati samo v seji, v kateri je bila njena vrstica Verify
dejansko izvedena in je uspela. To eno pravilo ubije večino agentnega
samočestitanja.

**Vsaka iteracija doda vrstico** v
[`loops/build/ledger.jsonl`](../loops/build/ledger.jsonl):
`{date, hypothesis, result, kept, rubric_delta, cost_eur}`. Ocene rubrik gredo v
`rubric_scores.jsonl` skupaj s hashem commita, razčlenitvijo po dimenzijah,
semenom in veljavnimi različicami pozivov. Dvaintrideset vrstic, €6,68 — celotna
ekonomika gradnje je en `jq` stran.

## 4. Nivoji znanja: da ponovno odkrivanje ni potrebno

Draga oblika odpovedi pri agentnem delu ni napaka. Draga je *ista napaka, tretjič
odkrita v četrti seji*. Berilo uporablja tri nivoje:

| Nivo | Kje | Vsebina | Cena |
|------|-----|---------|------|
| 1 — kanonična pravila | [`CLAUDE.md`](../CLAUDE.md) §9 | Pravila, ki so si mesto prislužila s ponavljanjem | Prebereš enkrat na sejo |
| 2 — register ugotovitev | [`docs/findings.md`](findings.md) | Datirane pasti in delujoči ukazi z dokazi | Preletiš pred delom |
| 3 — živa raziskava | znotraj seje | Kar ravno zdaj ugotavljaš | Drago — mora se zapisati v nivo 2 |

Pravilo napredovanja: ugotovitev se premakne z nivoja 2 na nivo 1, ko se ponovi
ali ko je potrjena. `CLAUDE.md` §9 trenutno vsebuje pet pravil in tri od njih so
stala pravi denar. Stop hook
([`.claude/hooks/reflect-on-session.sh`](../.claude/hooks/reflect-on-session.sh))
pred koncem vsake seje vsili krog razmisleka o dokumentaciji, tako da znanje
nivoja 3 ne izhlapi skupaj s kontekstnim oknom.

## 5. Štiri stvari, ki so šle narobe, in kaj so naučile

To je del, ki ga je vredno ukrasti.

### 5.1 Ključ predpomnilnika, ki je izpustil eksperiment, je ta eksperiment spremenil v prazen tek

Predpomnilnik prevodov je imel ključ `(book_hash, segment_hash, model, lang)`.
Poziva v ključu ni bilo. Vsak A/B test poziva bi zato ponovno postregel **stari**
prevod za €0 in poročal »ni izmerljive spremembe« — ničelni rezultat, ki ga ni
mogoče ločiti od pravega, pridobljen brez enega samega klica API.

Ujeto, preden je požgalo eksperiment. `prompt_version` se je pridružil primarnemu
ključu, obstoječe vrstice so bile migrirane na `baseline_v1`.

> **Pravilo, povišano na nivo 1:** preden zaupaš kateremukoli ničelnemu
> rezultatu, preveri, da ključ predpomnilnika vsebuje tisto, kar si spremenil.

### 5.2 Izmeri sodnika, preden nastavljaš tisto, kar sodnik ocenjuje

T3 (tekočnost) je pri vseh petih knjigah obtičal pri 12,4–13,5 od 20 — pri vsakem
formatu, pri vsaki zvrsti. Dimenzija, ki je nespremenljiva pri petih različnih
knjigah, je sistemska, ne lastnost knjige. Mamljiva poteza je, da začneš
prepisovati prevajalski poziv.

Namesto tega: **najprej izmeri sodnika.** €0,145 je kupilo 30 vzorcev, vsakega
ocenjenega 3-krat. Znotrajvzorčni σ je bil 0,18 — visoko ponovljivo — porazdelitev
sodb pa je pokrila celoten razpon, pri čemer je sodnik 5 od 30 vzorcev dal 5/5.
Sodnik torej ni bil s pozivom prisiljen v nizke ocene. Strop je bil resničen in
delati je bilo treba na prevajalskem koraku.

> **Pravilo, povišano na nivo 1:** sodnik, ki delu tvojega izhoda že daje
> najvišje ocene, ni tisto, kar te omejuje. Porazdelitev ocen je cenejši
> razločevalec kot kontrolna skupina človeško pisanega besedila.

### 5.3 Zmaga v tekočnosti je prišla iz drugega prehoda, ne iz boljšega poziva

Ko je bil sodnik izločen kot krivec, sta v parni, klastrsko bootstrapirani A/B
test na 6 zveznih odsekih × 10 segmentov (€0,26) šli dve hipotezi:

- `sl_style_v1` — izrecna slovenska slogovna pogodba v sistemskem pozivu (brez
  kalkov, glagolsko namesto samostalniško, izpuščanje odvečnih zaimkov, dvojina,
  šumniki): T3 **+0,05 [−0,10, +0,22]**. Nič. In T2 je nakazoval celo *poslabšanje*
  pomena.
- `revise_v1` — ista pogodba **plus ločen prehod urednika-domačega govorca čez
  vsako prevedeno serijo**: T3 **+0,48 [+0,17, +0,87]** in T2 **+0,23 [+0,07,
  +0,45]**. Obe dimenziji sta pridobili, obe spodnji meji intervala nad ničlo.

> Da modelu v navodilu rečeš, naj piše bolje, je precej šibkeje, kot da mu daš
> ločen krog, v katerem uredi tisto, kar je pravkar napisal — in urejevalni
> prehod je izboljšal tudi *zvestobo* izvirniku, tako da se pričakovani kompromis
> med slogom in pomenom sploh ni pojavil.

A/B test na 60 segmentih je napovedal **+1,93/20**; cela knjiga s 1294 segmenti
je dostavila **+1,9**. Hipoteze je torej mogoče presejati pri ~1/6 stroška in
~1/20 porabljenega časa, preden se zavežeš celi knjigi.

### 5.4 Merilni artefakt, zaradi katerega je ocena šla *gor*

*Active Measures* je dobil 78,6 — pod pragom 85. Preiskava je pokazala, da vzrok
ni bil prevod: rubrika v1.0 je vzorčila 989 drobcev citatov iz prevedenega
razdelka z opombami in štela terminološke zadetke znotraj besed. Rubrika v1.1
(samo osrednja proza, besedne meje) je isti EPUB ocenila z 85,0.

Kasneje se je zgodilo zrcalno. `The Revenge of Geography` je imel knjigi lastno
napako: tako `toc.ncx` kot navigacijski dokument se nista dala razčleniti, zato
je **94,9 % segmentov kot naslov poglavja podedovalo naslov knjige**, kar je
izničilo izločanje uvodnega in zaključnega gradiva in spustilo neprevedene
angleške naslove v bazen »osrednje proze«. Popravek je oceno premaknil z 89,0 na
**88,0** — *navzdol* — ker je sodnik za pomen tem neprevedenim naslovom dajal
5/5 (naslov se »prevede« popolnoma), sodnik za tekočnost pa 1/5.

> **Pravili, povišani na nivo 1:** popravek merilnega artefakta lahko oceno
> premakne v katerokoli smer — tak popravek sodi po čistosti bazena vzorcev, ne
> po smeri ocene. In kakovostna vrata popravljaj po **razredu** artefakta
> (tipiziraj, izloči ali zvij celotno kategorijo), nikoli tako, da loviš
> označene primerke: vsaka sprememba bazena na novo izžreba vzorec s fiksnim
> semenom, zato popravki po posameznih primerkih niso monotoni. To se je
> ponovilo trikrat, preden je bilo zapisano.

## 6. Koliko je stalo in kaj je nastalo

| | |
|---|---|
| Commitov | > 100 |
| Zabeleženih iteracij (vrstic dnevnika) | 32 |
| Skupna poraba za API, vse vključeno | **€6,68** |
| Prevedenih knjig EN→SL, v celoti | 5 |
| Rubrika T na teh knjigah | 85,0 – 88,5, spodnja meja IZ ≥ 82 pri vseh |
| Napak epubcheck | 0 |
| Testov | 239 (prevajalnik) + 180 (Android) |
| Porabljen čas | 2 dneva |

Kam je šlo €6,68: **€5,72** za šest tekov celih knjig in njihovo ocenjevanje,
**€0,87** za namensko merjenje — kalibracijo sodnika, A/B primerjavo, ponovno
oceno pod rubriko v1.1 in ponovno meritev po popravku — ter **€0,08** za
preizkusne teke med razvojem.

**Merjenje je pobralo 13 % proračuna in je razlog, da je preostalih 87 %
verodostojnih.** Dve od štirih lekcij iz §5 sta bili kupljeni s tistimi €0,87 in
vsaka od njiju bi po drugi poti stala več.

## 7. Kaj se prenese in kaj ne

**Prenese se na katerikoli agentni projekt:**

- Vrstico Verify napiši pred kodo. Če je ne znaš, še nimaš zgodbe, imaš željo.
- Naredi enega agenta nasprotnika drugemu. `plan-critic` pred gradnjo in
  `impl-reviewer` pred združitvijo sta ujela širjenje obsega in nepoštene teste,
  ki jih implementer strukturno ni mogel ujeti sam pri sebi.
- Skupno stanje z enim samim piscem. Vzporednost v kodi je v redu; vzporednost v
  datoteki z načrtom je kaos.
- Register ugotovitev z datumi in dokazi ter pravilo napredovanja na kratek
  kanonični seznam. Brez tega isto lekcijo plačuješ vedno znova.
- Nikoli ne pusti podagentu trošiti denarja ali se dotikati strojne opreme.

**Lastno temu projektu:** uteži rubrik, zgornja meja €5 na mejnik, oblikovalske
omejitve, ki izhajajo iz e-črnila Boox, in izbira `gpt-5-mini` pri sklepanju
`low` (privzeta stopnja sklepanja je v skritih žetonih sklepanja požgala ~4-krat
več od ocene — izmerjeno, nato popravljeno).

## 8. Preberi izvirnike

| Datoteka | Kaj vsebuje |
|----------|-------------|
| [`CLAUDE.md`](../CLAUDE.md) | Celotna delovna pogodba, §9 = kanonična naučena pravila |
| [`.claude/skills/orchestrate/SKILL.md`](../.claude/skills/orchestrate/SKILL.md) | Cevovod Supervisorja, faza za fazo |
| [`.claude/agents/`](../.claude/agents/) | Definicije petih agentov dobesedno |
| [`docs/rubric.md`](rubric.md) | Postopki ocenjevanja, uteži, pragovi |
| [`docs/project_plan.md`](project_plan.md) | Vsaka zgodba z vrstico Verify in izidom |
| [`docs/findings.md`](findings.md) | Register nivoja 2 — 52 datiranih ugotovitev |
| [`loops/build/ledger.jsonl`](../loops/build/ledger.jsonl) | Ena vrstica na iteracijo, s stroškom |
| [`loops/build/rubric_scores.jsonl`](../loops/build/rubric_scores.jsonl) | Vsaka ocena s commitom, semenom in razčlenitvijo po dimenzijah |

Licenca MIT. Prekopiraj dele, ki delujejo.
