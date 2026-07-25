# Berilo

**Beri knjige v svojem jeziku.**

🇬🇧 **English:** [README.md](README.md)

<p align="center">
  <img src="assets/social/berilo-hero.png" alt="Berilo — od želje po slovenskem prevodu do končane knjige na e-bralniku, prek agentne zanke hipoteza → izgradnja → pregled → preverjanje → obdrži ali popravi" width="620">
</p>

Berilo prevede knjige (PDF / EPUB / MOBI) v tvoj jezik s poceni jezikovnimi
modeli — zgrajeno tako, da ohrani pomen, ne le besede — in ti da bralnik za
ljudi, ki dejansko berejo: slovar z razlago v kontekstu, interpretacijo gostih
odstavkov, označbe in zapiske.

Pet knjig je šlo skozi celoten postopek, iz angleščine v slovenščino, po
€0,60–€1,45 na knjigo. Kakovost prevoda je izmerjena, ne zatrjena: 85,0–88,5 na
100-točkovni rubriki z bootstrap intervali zaupanja.

## Je zastonj?

**Da.** Berilo je programska oprema pod licenco MIT. Ni računa, ni naročnine, ni
strežnika Berilo in ni telemetrije. Android aplikacija zahteva natanko eno
dovoljenje — `INTERNET` — in ga uporabi samo za dostop do ponudnika modela, ki si
ga nastavil sam.

Plačaš enemu samemu naslovniku, in to nismo mi: **svojemu ponudniku jezikovnega
modela**, po žetonih, po njihovem ceniku. Cela knjiga stane približno €1. Glej
[Koliko stane](#koliko-stane).

### Tvoj API ključ ostane pri tebi

| Kje | Shramba | Ali gre z naprave? |
|-----|---------|--------------------|
| Prevajalnik (CLI) | `.env` v mapi projekta (v `.gitignore`) | Nikoli — samo do API-ja ponudnika |
| Android aplikacija | `EncryptedSharedPreferences`, `allowBackup=false` | Nikoli — samo do API-ja ponudnika |

Konkretno:

- Ključ gre iz tvojega `.env` oziroma s tvojega telefona naravnost na
  `api.openai.com` ali `api.anthropic.com`. Vmesnega člena ni, ker zaledje
  Berila ne obstaja.
- **Tvoje knjige nikoli ne zapustijo tvoje naprave.** Ponudniku se pošljejo samo
  odseki besedila, ki se prevajajo — enako, kot če bi odstavek prilepil v
  pogovorno okno, po eno serijo naenkrat.
- Ključi se nikoli ne zapišejo v dnevnik. V CLI razred `Config` označi polja s
  ključi z `repr=False`, zato se izpustijo iz vsakega izpisa ali dnevniškega
  zapisa konfiguracije. V aplikaciji testi preverjajo, da sporočilo napake pri
  neuspeli avtentikaciji *in* njegov vzrok ne vsebujeta ključa.
- Če hočeš trdo zgornjo mejo, nastavi omejitev porabe pri ponudniku. Berilo tudi
  samo ne zažene plačljivega teka, dokler ti ne pokaže ocene stroška.

### Kako dobiš ključ

**OpenAI** (privzeto, za to nalogo najceneje) — <https://platform.openai.com/api-keys>
→ *Create new secret key* → naloži nekaj evrov dobroimetja → prilepi v `.env` kot
`OPENAI_API_KEY`.

**Anthropic** (alternativa) — <https://console.anthropic.com/settings/keys>
→ *Create Key* → prilepi v `.env` kot `ANTHROPIC_API_KEY`.

Dovolj je en ponudnik. Privzeti model je `gpt-5-mini`; vsak model lahko zamenjaš
z zastavico `--model`.

## Koliko stane

Izmerjeni teki celih knjig, angleščina → slovenščina, `gpt-5-mini`, sklepanje
nastavljeno na `low`. Rubrika T je ocena kakovosti prevoda (0–100) s 95 %
bootstrap intervalom zaupanja.

| Knjiga | Vir | Besed | Slog | Strošek | Rubrika T |
|--------|-----|-------|------|---------|-----------|
| The New Rules of War | EPUB | 82 k | `baseline_v1` | €0,60 | 88,5 [86,0, 90,8] |
| Sandworm | EPUB | 105 k | `baseline_v1` | €0,75 | 88,5 [86,2, 90,5] |
| The Revenge of Geography | EPUB | 124 k | `revise_v1` (privzeto) | €1,45 | 88,0 [86,2, 89,9] |
| Active Measures | PDF | 144 k | `baseline_v1` | €1,01 | 85,0 [82,6, 87,5] |
| This Is How They Tell Me the World Ends | skeniran PDF (OCR) | 182 k | `baseline_v1` | €1,22 | 86,7 [84,3, 89,0] |

Preračunano, pri ~300 besedah na tiskano stran:

| | na 100 strani (~30 k besed) | na 100 k besed | običajna knjiga 350 strani |
|---|---|---|---|
| **`revise_v1`** — privzeto, doda drugi prehod urednika-domačega govorca | **≈ €0,35** | ≈ €1,17 | **≈ €1,20** |
| `baseline_v1` — en prehod, `--style baseline_v1` | ≈ €0,20 | ≈ €0,70 | ≈ €0,75 |

Privzeti slog stane približno 1,7-krat toliko kot en sam prehod in prinese
izmerjenih +4,1 točke rubrike T na celi knjigi (83,9 → 88,0, intervala zaupanja
sta skoraj disjunktna). Poceni je tako ali tako.

Druga poraba:

- `berilo translate --dry-run` — **€0,00.** Izpiše oceno žetonov po poglavjih in
  skupni strošek, preden se karkoli zaračuna.
- `berilo doctor` — en stavek, ~€0,0001.
- `berilo eval --sample 40` — ~€0,15 na ocenjevalni tek.
- **Ponovni teki so zastonj.** Vsak preveden odsek se predpomni, ključ vsebuje
  knjigo, odsek, model, jezik *in* različico poziva. Če tek prekineš in ga znova
  zaženeš, plačaš samo tisto, kar še ni bilo narejeno.

Cene so stanje 2026-07 (`translator/berilo/providers/pricing.py`, USD→EUR 0,92).
Preveri jih pri svojem ponudniku, preden se zaneseš na absolutne zneske.

## Stanje

| Faza | Kaj | Stanje |
|------|-----|--------|
| 1 | Prevajalnik CLI (`translator/`) — knjiga noter, prevedeni EPUB ven | deluje; 5 knjig prevedenih, rubrika T ≥ 85 pri vseh |
| 2 | Android bralnik (`android/`) — brez povezave, prijazen do e-črnila (Boox) | koda dokončana, 180 testov zelenih; preverjanje na napravi še teče |
| 3 | Sinhronizacija v oblaku + spletni pregled zapiskov (zaprta koda) | načrtovano |

Načrt in merljiva merila sprejemljivosti: [`docs/project_plan.md`](docs/project_plan.md).
Rubrike kakovosti, ki jih projekt optimizira: [`docs/rubric.md`](docs/rubric.md).

## Prevajalnik CLI (faza 1)

```bash
cp .env.example .env             # vpiši svoj OpenAI ali Anthropic ključ
cd translator && pip install -e .

berilo doctor                    # preizkus ponudnika, en stavek, ~€0
berilo inspect mojaknjiga.epub   # predogled izvlečka, brez stroška API
berilo translate mojaknjiga.epub --to sl --dry-run   # ocena stroška — vedno najprej
berilo translate mojaknjiga.epub --to sl             # prevedeni EPUB ob izvirniku
berilo eval mojaknjiga.sl.epub --sample 40 --seed 42 # ocena kakovosti z IZ (~€0,15)
```

Uporabne zastavice:

| Zastavica | Učinek |
|-----------|--------|
| `--to sl` | Ciljni jezik (privzeto iz `.env`, `sl`) |
| `--dry-run` | Oceni strošek, brez enega samega klica API |
| `--style baseline_v1` | En prehod, ~40 % ceneje, izmerljivo manj tekoče |
| `--model gpt-5` | Katerikoli model iz cenika |
| `--bilingual` | Izpiše dvojezični EPUB (izvirnik + prevod) |
| `--skip-back-matter` | Kazalo, opombe in bibliografijo pusti neprevedene |
| `-o izhod.epub` | Pot izhodne datoteke |

Potrebuje Python 3.10+. Za vhod v obliki MOBI je potreben še Calibre
(`ebook-convert`).

## Bralnik (faza 2, Android / Boox)

Bralnik EPUB brez povezave (na osnovi Readiuma) s slovarjem, interpretacijo
odstavkov, označbami in zapiski. Bere tisto, kar je izdelal prevajalnik — oblak
ni potreben.

Grajen najprej za e-črnilo: brez animacij, obračanje strani s polnim osvežitvijo,
čista črno-bela tema, tipne tarče ≥ 48 dp, kontrast po WCAG AA.

**Prevedi iz izvorne kode** (dokler izdaja v0.1 ni objavljena):

```bash
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Namestitev APK z izdaje** na Boox, ko bo na
[Releases](https://github.com/nikogamulin/berilo/releases) na voljo: v
**Nastavitve → Aplikacije → Poseben dostop → Namesti neznane aplikacije**
dovoli namestitev brskalniku ali upravitelju datotek, nato na napravi odpri
prenesen APK.

## Kako je nastalo

Berilo je zgradila nadzorovana večagentna zanka — po ena hipoteza naenkrat, vsaka
izmerjena z rubriko, preden je smela ostati. Več kot 100 commitov, 32 zabeleženih
iteracij, €6,68 porabe za API, dva dneva.

Celotna metoda — vloge agentov, merilna zanka in štiri stvari, ki so šle narobe,
ter kaj so naučile — je opisana tu:

📄 **[Kako je Berilo nastalo z agenti](docs/how_it_was_built.sl.md)** ·
🇬🇧 [in English](docs/how_it_was_built.md)

<p align="center">
  <img src="assets/social/berilo-concrete-build-loop.png" alt="Razvojna zanka Berila: 27 poskusov, 5 prevedenih knjig, €4,42 zabeleženega stroška, 0 napak EPUB" width="620">
</p>

## Razvoj

```bash
# Prevajalnik — 239 testov
cd translator && pip install -e ".[dev]"
make test && make lint

# Android — 180 testov
cd android
./gradlew assembleDebug test lintDebug   # vsakodnevna zanka
./gradlew assembleRelease                # minificirana izdaja (podpisana z debug
                                          # ključem, dokler ne obstaja
                                          # android/keystore.properties —
                                          # glej keystore.properties.example)
```

## Načela

- Tvoje knjige in tvoji ključi ostanejo pri tebi. Ponudniku se pošljejo samo
  odseki besedila.
- Kakovost prevoda je izmerjena, ne predpostavljena — glej ocenjevalni pripomoček
  v [`docs/rubric.md`](docs/rubric.md).
- Stroški so vidni, preden nastanejo. Noben plačljiv tek se ne začne brez ocene.
- Privzeto se uporabi najcenejši model, ki opravi nalogo; vsakega lahko zamenjaš.
- Nobenih funkcij za piratstvo. Datoteke priskrbiš sam.

## Licenca

MIT — Niko Gamulin, PhD. (Fazi 1–2 sta odprtokodni; neobvezna sinhronizacija v
oblaku je ločen produkt z zaprto kodo.)
