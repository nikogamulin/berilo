# Berilo — moodboard za spletno in Android aplikacijo

> Osrednja ideja: **tiha slovenska uredniška sodobnost**. Berilo naj deluje kot
> skrbno oblikovana knjiga in osebna čitalnica, ne kot tehnološki izdelek, ki
> zahteva pozornost.

## Občutek znamke

**Umirjeno · razgledano · domače**

Berilo bi kot oseba nosilo dobro krojen volnen plašč, usnjeno torbo in knjigo z
zavihanim listkom. Pozna Plečnikovo disciplino, modernistično slovensko knjižno
oblikovanje in toplino večernega branja, vendar ni nostalgično.

Nikoli ne sme delovati kot generična AI-aplikacija, igrificiran sledilnik navad,
bleščeča tehnološka nadzorna plošča ali imitacija luksuza.

## Tri vizualne smeri

### 1. Tiha čitalnica — priporočena

- **Reference:** slovenska knjižna oprema, Plečnikova ritmična geometrija,
  čitalniške kartice, naraven nepremazan papir, diskretne uredniške oznake.
- **Paleta:** papir `#F7F3EA`, črnilo `#171512`, globoki jantar `#B45309`,
  kost `#E8E0D2`, utišana olivna `#77725B`.
- **Tipografija:** Literata za vsebino in izrazne naslove; Inter za navigacijo,
  metapodatke in dejanja.
- **Postavitev:** velikodušen rob, jasna navpična os, knjižni proporci, tanki
  ločilniki in veliko mirnega prostora.
- **Podpisni elementi:** jantarna bralna nit, številke poglavij v širokem robu,
  kartice kot listi papirja, rahla tekstura vlaken samo na spletu.

### 2. Jantarni večer

- **Reference:** svetloba namizne svetilke, temen les, usnje, zaznamki,
  intimnost večernega branja.
- **Paleta:** smetana `#FFF8EB`, espresso `#211A16`, jantar `#B45309`,
  terakota `#8D4E35`, pesek `#D8C7AE`.
- **Postavitev:** mehkejši sloji in bolj izrazite kartice; odlična za spletni
  uvod in OLED temno temo.
- **Tveganje:** preveč dekorativnosti bi oslabilo e-ink čistost.

### 3. Črno-beli modernizem

- **Reference:** slovenski modernistični plakati, stroga uredniška mreža,
  časopisna hierarhija, suha žigosana oznaka.
- **Paleta:** bela `#FFFFFF`, črna `#0A0A0A`, jantar `#B45309`.
- **Postavitev:** izrazita mreža, veliki naslovi, skoraj brez površinskih
  učinkov.
- **Tveganje:** lahko deluje hladno in institucionalno.

## Izbrana sinteza

Osnova je **Tiha čitalnica**, spletni uvod si izposodi toplino **Jantarnega
večera**, e-ink način pa strogost **Črno-belega modernizma**.

### Barvna hierarhija

| Vloga | Svetla tema | Temna tema | E-ink |
|---|---|---|---|
| Ozadje | `#F7F3EA` | `#171512` | `#FFFFFF` |
| Površina | `#FFFCF6` | `#211E1A` | `#FFFFFF` |
| Besedilo | `#171512` | `#F5F0E7` | `#000000` |
| Sekundarno | `#5F5A51` | `#C3BAAC` | `#000000` |
| Poudarek | `#B45309` | `#D97706` | `#000000` + vzorec |
| Ločilnik | `#D8D0C2` | `#403A33` | `#000000` |

Jantar označuje samo primarno dejanje, aktivno mesto ali bralno nit. Nikoli ni
dekorativna razlivna barva.

### Materiali in motivi

- nepremazan, rahlo topel papir;
- črnilo z zelo ostrimi robovi;
- tanki zaznamki in knjižni trakovi;
- abstraktna geometrija, navdihnjena z ritmom arkad in knjižnih polic;
- drobne uredniške oznake: poglavje, stran, datum, jezik;
- brez fotografij za besedilom, bleščečih gradientov ali AI-orbov.

## Spletna aplikacija

Splet je **osebna čitalnica za pregled in ponovno srečanje z zapiski**.

- Namizna postavitev uporablja 12-stolpčno mrežo; vsebina ostaja v knjižnem
  jedru širine približno 1120 px.
- Leva navigacija je stalna in ozka: knjižnica, zapiski, besedišče. Brez
  hamburgerja na namizju.
- Uvodna stran pokaže en jasen obljubni stavek in eno primarno dejanje.
- Knjige so prikazane kot naslovnice, ne kot generične SaaS-kartice.
- Zapiski imajo tanko barvno levo črto; citat je v Literati, uporabnikov zapis
  v Interju.
- Globina prihaja iz prekrivanja papirnatih ploskev in komaj zaznavne sence,
  ne iz steklenih učinkov.
- Mobilni splet postane enostolpčen; spodnja navigacija ima največ štiri cilje
  in 48 px velike dotikalne površine.

## Android aplikacija

Android je **knjiga, ki občasno razkrije orodje**.

- Med branjem ni stalnega uporabniškega vmesnika.
- Dotik sredine razkrije tih zgornji in spodnji sloj: poglavje, napredek,
  nastavitve.
- Slovar se odpre kot spodnji list do 40 % višine; razlaga odstavka do 70 %.
- Telo je Literata, najmanj 24 dp roba, 55–70 znakov v vrstici in višina vrstice
  1,5.
- Knjižnica uporablja naslovnice z zelo tanko spodnjo črto napredka.
- Na Booxu so vse površine čisto bele, besedilo čisto črno, brez animacij in
  sivih nizkokontrastnih kontrol.
- Štirje označevalniki se v barvi razlikujejo z utišanimi polnili, v e-ink
  načinu pa tudi z vzorci: polno, diagonalno, pikčasto, vodoravno.

## Oblikovne specifikacije

- **Prostorska lestvica:** 4, 8, 12, 16, 24, 32, 48, 64.
- **Radij:** 2 px za knjižne ploskve, 8 px za interaktivne liste, 999 px samo
  za majhne oznake.
- **Senca na spletu:** `0 12px 36px rgba(31, 24, 16, 0.08)`; brez sence na
  e-inku.
- **Gibanje:** 160 ms za odziv, 240 ms za menjavo sloja; v e-ink načinu nič.
- **Dotik:** najmanj 48 dp.
- **Kontrast:** najmanj WCAG AA; telo nikoli v svetlo sivi.
- **Modularna tipografska lestvica:** 1,25.

## Ključni prizori moodboarda

1. Spletna knjižnica na toplem papirnatem ozadju z naslovnicami in diskretno
   jantarno bralno nitjo.
2. Namizni pregled zapiskov: citat v Literati, komentar v Interju, mirna
   uredniška mreža.
3. Boox bralnik brez kroma: samo črno besedilo, velik rob in številka poglavja.
4. Slovarski spodnji list z besedo **razgledan**, kratkim prevodom in stavkom v
   kontekstu.
5. Materialni vzorci: papir, črnilo, jantarni trak, utišana olivna tkanina.
6. Tipografski preizkus s šumniki: **Človek bere, da bi bolje videl.**

## Glas znamke

Slovenščina je naravna, jasna in nevpadljiva. Naslovi uporabljajo stavčno
veliko začetnico. Besedilo ne govori o »revolucionarni AI«, temveč o dejanju:
**Prevedite svojo knjigo. Berite jo po svoje.**

Primeri mikrobesedila:

- **Dodaj knjigo**
- **Nadaljuj branje**
- **Poišči v zapiskih**
- **Pomen v tem odstavku**
- **Vaše knjige ostanejo pri vas.**

## Zavrniti pri oblikovnem pregledu

- več kot eno poudarno barvo;
- generične gradientne AI-ilustracije;
- lebdeče steklene kartice;
- besedilo čez fotografijo;
- kričeče odstotke napredka;
- značke, nize, lestvice in obvestila za vračanje;
- animacije v e-ink načinu;
- navigacijo, ki med branjem ostane na zaslonu.
