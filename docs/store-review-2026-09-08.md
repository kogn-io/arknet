# arknet Store-Review -- 2026-09-08

Ein vollstaendiger Durchlauf nach `/arknet:store-review`: erst die mechanische
Ebene (Werkzeugbefunde, unveraendert uebernommen), dann die Leser-Ebene je
Ressourcentyp, jeweils in einem eigenen Subagenten ohne den Schreibkontext
dieser Session -- der Reviewer ist nirgends der Autor.

Dieser Bericht **aendert nichts**. Kein `*_add`, `*_update`, `*_set_status`,
`*_link_*`, `*_delete`, `*_supersede` wurde aufgerufen. Was aus einem Fund
folgt, entscheidet der Leser gegen das jeweilige Fachskill.

Sprache: Deutsch, weil der gepruefte Bestand ueberwiegend deutsch zitiert wird.
Das weicht von der Englisch-Regel fuer ausgelieferte Artefakte ab.

## 1. Scope

| Ressourcentyp | Anzahl | Leser-Review |
|---|---|---|
| Architecture Decision Record | 30 | ja (Regeltabelle `/arknet:adr`) |
| Requirement (FR/NFR) | 11 | ja (Checkliste `/arknet:req-interview`) |
| Constraint | 0 | entfaellt mangels Ressourcen |
| Use Case | 4 | ja (Checkliste `/arknet:req-interview`) |
| Glossarbegriff | 27 | ja (Checkliste `/arknet:req-interview`) |
| Bounded Context | 6 | eingeschraenkt (kein Pruefmodus, s. Abschnitt 7) |
| Context Relationship | 13 | kein Pruefmodus, nur Leserbefund |
| Actor | 0 | kein Pruefmodus |
| Role | 4 | kein Pruefmodus |

Projekt: `arknet`, aufgeloest ueber den Verzeichnis-Anker dieses Repositoriums.
Gefuehrte Sprachen: de, en.

## 2. Mechanische Ebene (Fakten)

### 2.1 `adr_check` -- 30 Entscheidungen, 20 Fakten, 2 Verdachtsmomente

**Fakten.** Weder `addressesRequirement` noch `affectsContext` bei:
ADR-3, ADR-4, ADR-6, ADR-7, ADR-8, ADR-10, ADR-12, ADR-13, ADR-14, ADR-16,
ADR-19, ADR-28, ADR-32, ADR-45, ADR-46, ADR-49, ADR-51, ADR-55, ADR-56, ADR-58.

**Verdacht (ein Leser entscheidet).**
- ADR-4: Statusprosa in der Entscheidung ("noch nicht") -- beim Schreiben wahr,
  spaeter stillschweigend falsch.
- ADR-46: fast identischer Titel zu ADR-4.

**Was `adr_check` nach eigener Auskunft nicht prueft** (und was darum die
Leser-Ebene tragen muss): ob ein Record mehr als eine Entscheidung traegt,
ob zwei Records einander widersprechen, ob eine Consequence etwas sagt.

### 2.2 `orphan_check`

Requirements ohne realisierenden Use Case (6): FR-6, FR-7, FR-8, FR-9, FR-10, NFR-1.

Nie referenzierter Begriff (1): TERM-31 "Considered Option".

Im Text erwaehnt, ohne Kante (8): BC-6 nennt "Role" (TERM-21) und "Term"
(TERM-13) ohne `ubiquitousLanguageTerm`; ADR-37 nennt "Term" und "Project",
ADR-48 "Project", ADR-32 "Role", ADR-53 und ADR-56 "Term" -- jeweils ohne
`usesTerm`.

Constraints ohne Bindung: keine (der Bestand ist leer).

### 2.3 `store_check`

`LANGUAGE` (gefuehrte Sprachen de, en): sieben `skos:prefLabel` ohne `en` --
TERM-27, TERM-28, TERM-29, TERM-30, TERM-31, TERM-32, TERM-33.

`ROLE_TERM_DUPLICATE`: keine Namensgleichheit zwischen Rolle und Begriff.

**Nicht gesehen:** ein Feld ganz ohne Sprachtag ist von einem einsprachigen
Feld nicht unterscheidbar und wird nie gemeldet.

### 2.4 `trace_matrix`

Realisiert: FR-1 durch UC2, FR-2 durch UC1, FR-3 durch UC3, FR-4 durch UC2+UC3,
FR-5 durch UC4. Ohne Realisierung: FR-6, FR-7, FR-8, FR-9, FR-10, NFR-1.
NFR-1 verwendet ausserdem keinen einzigen Begriff (`usesTerm` leer).
## 3. Architecture Decision Records (30) -- Leser-Ebene

Regelquelle: die Regeltabelle aus `Reviewing the ADRs in the store` in
`/arknet:adr`. Alle 30 Records wurden vollstaendig gelesen (`adr_get`,
`displayLocale: de`). Leere Zelle kommt nicht vor.

| Record | R0 Wuerdig | R1 Eine Entscheidung | R2 Kein Impl.-Detail | R3 Keine ext. Referenz | R4 Keine Statusprosa | R5 Substanz Folgen/Optionen | R6 Referenzen passen | R7 Prosa=Graph | R8 Status ehrlich |
|---|---|---|---|---|---|---|---|---|---|
| ADR-3 | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| ADR-4 | ok | Auslagerung der Kontextliste in ein Constraint ist 2. Entscheidung | ok | ok | Verdacht entkraeftet: "noch nicht gebaut" ist zitierte Begriffsunterscheidung, kein Zustandsbericht | ok | ok | ok | ACCEPTED und SUPERSEDED am selben Tag |
| ADR-6 | ok | ok | ok | ok | ok | duenn (je 1 pos/neg), aber tragend | ok | ok | ok |
| ADR-7 | ok | Bericht-Komposition ist 2., unabhaengige Entscheidung | ok | ok | ok | ok | ok | ok | ok |
| ADR-8 | ok | ok | ok | ok | ok | duenn (je 1) | ok | ok | ok |
| ADR-10 | Kontextliste ist Bausteinsicht, nicht Entscheidung | "Kontextgrenze != Modulgrenze" ist 2. Entscheidung | ok | ok | ok | ok | keine Kante auf BC-1/2/3, deren Inhalt er festlegt | ok | inhaltlich von ADR-52 ersetzt, ohne Kante |
| ADR-12 | ok | ok (Klausel "nicht entschieden" ist Abgrenzung) | ok | ok | ok | ok | BC-1/BC-2 waeren die gebundenen Kontexte | ok | ok |
| ADR-13 | ok | ok | ok | ok | ok | ok (starke Negativfolge) | keine Kante auf BC-5, anders als ADR-20/25/27/53 | ok | wird von ADR-53 umgekehrt |
| ADR-14 | ok | "Composition Root ist Spring-Boot-Anwendung" separabel | ok | ok | ok | ok | ok | ok | ok |
| ADR-16 | ok | Betriebsform (Daemon) und Transportwahl (HTTP/Loopback) separabel | ok, kein Portliteral | ok | ok | ok, stark | ok | ok | ok |
| ADR-19 | ok | ok | ok | ok | ok | ok | ok | ok | ok (Regel vor dem Code) |
| ADR-20 | ok | Fehlerverhalten bei unbekanntem Anker + n:1-Anker separabel | ok | ok | ok | ok | ok (BC-5) | ok | ok |
| ADR-25 | ok | "keine Zwischenebene Arbeitsbereich" separabel | ok | ok | ok | ok | ok (BC-5) | ok | ok |
| ADR-27 | ok | ok | ok | ok | ok | ok | ok (BC-5) | ok | ok |
| ADR-28 | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| ADR-32 | ok | ok | ok | ok | ok | Negativfolge pflichtschuldig; echter Preis der Doppelrolle ungenannt | ok | ok | ok |
| ADR-36 | ok | ersatzloser Wegfall der Glossar-Facette ist 2. Entscheidung (Breaking Change) | ok | ok | ok | ok, stark | ok (BC-4) | ok | ok |
| ADR-37 | ok | Kontextzuordnung der Rolle separabel von der Ressourcentrennung | ok | ok | ok | ok, stark | ok (FR-7, BC-4) | ok | ok |
| ADR-45 | ok | "Feld-Delta statt Objekt-Abzug" ist eigene Schreibstrategie | ok | ok | ok | ok | ok | ok | ok |
| ADR-46 | Acht-Kontexte-Liste ist Aufzaehlung; Entscheidung ist das Schnittkriterium | 3 unabhaengige Aussagen (Liste / Ausschluss Erzeugnis / Methodenneutralitaet) | ok | Kontext erzaehlt die Entstehung des eigenen Records ("Die erste Fassung ... war eine Fehlklassifikation") | ok | ok | ok | ok | ok |
| ADR-48 | konkrete Parent-Zuordnung ist Bausteinsicht | 3 Aussagen: Komponenten bleiben / BC=Maven-Parent / Hexagon-Modulform | ok | "Der erste Entwurf dieser Entscheidung ..." + "Seither ist grundsaetzlich geklaert ..." | ok | ok | nur BC-1/BC-2, obwohl der Text sechs Kontexte bindet | ok | ok |
| ADR-49 | ok | "liest nur, schreibt nie" separabel | ok | "Der erste Entwurf dieser Entscheidung ..." (auch in Option 2) | ok | ok, stark | ok | ok | ok |
| ADR-51 | ok | unveraenderte Regel + neuer Ausnahmezuschnitt gebuendelt | ok | unbenannter "weiterer Vorschlag" aus der Beratung | "geduldeter Behelf, bis eine Lesefläche ihn abloest"; Folge 3/4 "bis der Umzug erfolgt ist" | ok | ok | ADR-55 loest ihn ab, Kante nur relatedTo | ACCEPTED, aber von PROPOSED-Record bestritten |
| ADR-52 | Kontextliste + Zuordnung = Bausteinsicht; Schnittkriterium steht in ADR-46 | Kern-/Stuetzliste separabel; Stuetzteil dupliziert ADR-36/53/54 | ok | "Seither ist grundsaetzlich geklaert, was ein BC im Build ist" (ruht auf PROPOSED ADR-48) | "Die Liste ist der Bauzustand gegen den Zielschnitt" | Folge 3 wiederholt nur die Entscheidung | nur die 3 stuetzenden BCs von 6 entschiedenen | ersetzt ADR-10/ADR-13 ohne Ablösekante | im Modell (bc_list) bereits umgesetzt |
| ADR-53 | Mandanten-Teil ist vertagte Planung, keine Entscheidung | Registry-als-BC und Mandanten-Abgrenzung separabel | ok | "Drei Umstaende haben sich seither geaendert" (einer davon = PROPOSED ADR-56) | "sobald Mandantenfaehigkeit konkret wird" | ok | ok (BC-5) | ersetzt ADR-13 ohne Ablösekante | im Modell bereits umgesetzt |
| ADR-54 | ok | Trennung Lesemodell/Darstellung ist eigene Aussage | Aufzaehlung der fuenf Auswertungen ist Bestandsliste, nicht Kriterium | "Drei Orte waren bereits verworfen ..." | ok | ok, stark | ok (BC-6) | ok | im Modell bereits umgesetzt |
| ADR-55 | unveraenderte Regel-Haelfte ist Status quo (Umkehr kostet nichts) | Regel + Ausnahmezuschnitt erneut gebuendelt | ok | Praemisse "Seit die Modellanalyse ein eigener Kontext ist" ruht auf PROPOSED ADR-54 | ok | ok (weitgehend aus ADR-51 uebernommen) | ok | behauptet Abloesung, Kante ist nur relatedTo | widerspricht dem ACCEPTED ADR-51 |
| ADR-56 | ok | Aufnahmeregel vs. konkrete Inhaltsliste separabel; "an keinem anderen Modul" 3. Aussage | Folge 5 verschiebt konkrete Mechanik in Support-/Persistenzmodule | ok | ok | Folge 5 ist eine zweite Entscheidung im Folgen-Gewand | ok (projektweit, korrekt kantenlos) | ok | ok |
| ADR-57 | ok | Verweistyp aufs Glossar ist eigene Aussage | ok | ok | ok | ok | ok (BC-1/BC-2, praezise) | ok | ok |
| ADR-58 | Status quo: kein API-Modul existiert; die eigene Neutralfolge nennt die Umkehr billig -> Q2 faellt | "eine Schnittstelle je Anwendungsfall" separabel | ok | ok | ok | ok | ok | ok | ok |

### 3.1 Belegsammlung: Zerlegung des Entscheidungstextes (R1)

- **ADR-3**: (a) alle Schreibpfade durch einen Trichter; (b) er schreibt Modelldaten+Revision+Head atomar. (b) ist der Inhalt von (a), keine zweite Wahl. Eins.
- **ADR-4**: (a) Zielschnitt ueber den Lifecycle; (b) die Kontextliste haelt ein Constraint; (c) Realisierungsstand ist Bauzustand; (d) Unterscheidung ungebaut/ausserhalb; (e) gegen Normen geprueft. (b) konnte anders ausfallen, ohne (a) zu aendern -- und tat es (ADR-46). (e) ist Provenienzprosa, keine Entscheidung.
- **ADR-6**: (a) lokaler Single-User-Client; (b) keine Auth-/Mandanten-/Team-Begriffe in den Cores; (c) kein vorbereiteter Haken; (d) Multi-User traegt spaeter das Backend. (b)=(c)=(d) sind dieselbe Aussage; (a) ist Praemisse. Eins.
- **ADR-7**: (a) kein eigener Kontext fuer den Lesepfad; (b) er lebt im Root als zwei Werkzeuge; (c) fachliche Sichten bleiben bei den Kontexten; (d) der Bericht wird je Kontext komponiert. (c)/(d) konnten anders ausfallen, ohne (a)/(b) zu beruehren -- genau diese Haelfte musste ADR-51/ADR-55 neu entscheiden.
- **ADR-8**: (a) Persistenz nur ueber domaennahe Out-Ports; (b) keine Triples/Query-Sprache in der Signatur; (c) Backend-Wechsel = Adapter-Austausch. (b) definiert (a), (c) ist Folge. Eins.
- **ADR-10**: (a) drei Kontexte mit Inhalten; (b) Liste ist kein geschlossener Katalog; (c) spaetere Typen bekommen eigene Entscheidung; (d) Kontextgrenze ist nicht Modulgrenze. (d) konnte anders ausfallen (Kontext=Hexagon), ohne (a) zu aendern -- ADR-52 nennt genau diese Gleichsetzung als Fehler der Vorgaengerentscheidung. Zwei.
- **ADR-12**: (a) kein Namespace ueber zwei Kontexte; (b) Aufloesung per Teilung oder Schnittkorrektur; (c) ausdruecklich keine 1:1-Zuordnung; (d) Migration nur bei Regelverstoss. (b)/(d) sind Korollare von (a); (c) ist Abgrenzung. Eins.
- **ADR-13**: (a) Registry ausserhalb der Modell-Kontexte; (b) sie verwaltet Identitaet, nicht Modell; (c) ein Schnitt laesst sie unberuehrt. (b) Begruendung, (c) Folge. Eins.
- **ADR-14**: (a) Spring AI als Technologie-Linie; (b) Tools als annotierte Methoden, Schema abgeleitet; (c) Root ist Spring-Boot-Anwendung; (d) kein rohes SDK; (e) Transport offen. (c) konnte anders ausfallen (Bibliothek ohne Boot-Anwendung), ohne (a)/(b) zu aendern. Zwei (schwach).
- **ADR-16**: (a) langlebiger Daemon statt Subprozess; (b) HTTP-Transport auf festem Loopback-Port; (c) bedient alle Projekte; (d) erwirbt pro Aufruf das benannte Dataset. (b) konnte anders ausfallen (Unix-Socket), ohne (a)/(c) zu aendern -- und ADR-14 hatte den Transport ausdruecklich offen gelassen. Zwei.
- **ADR-19**: (a) kein eigener BC fuer fremdgefuehrte Konzepte; (b) der konsumierende Kontext uebersetzt an seiner Grenze; (c) nur benoetigte Felder, eigene Begriffe. (b)/(c) sind die Positivform von (a). Eins.
- **ADR-20**: (a) Identitaet registriert statt abgeleitet; (b) opaker Anker mit Typ, Server interpretiert nie; (c) mehrere Anker auf ein Projekt; (d) Aufruf ohne/mit unbekanntem Anker scheitert, kein Default. (d) konnte anders ausfallen (stiller Fallback) -- es steht als eigene verworfene Option im selben Record. (c) ebenfalls unabhaengig variierbar. Drei (schwach).
- **ADR-25**: (a) ein Dataset je Projekt; (b) Ausnahme reserviertes Registry-Dataset; (c) keine Zwischenebene Arbeitsbereich. (c) konnte anders ausfallen, ohne (a) zu beruehren -- eigene verworfene Option. (b) ist inhaltsgleich mit ADR-27. Zwei.
- **ADR-27**: (a) Registry im selben Substrat, eigenes Dataset unter reservierter Kennung; (b) als einzige nicht projekt-gebunden; (c) Kennung fuer ein Projekt gesperrt. (b)/(c) sind Korollare. Eins.
- **ADR-28**: (a) jede Modelaenderung erzeugt unveraenderliche Revision; (b) ohne Ausnahme. (b) ist der Geltungsbereich von (a). Eins.
- **ADR-32**: (a) Revision in Doppelrolle; (b) Head je Ressource abfragbar; (c) bedingter Write prueft Head in der Transaktion. (b)/(c) sind der Mechanismus von (a). Eins.
- **ADR-36**: (a) Actor wird eigener BC mit Lebenszyklus und Tool-Oberflaeche; (b) Actor darf zusaetzlich Glossarbegriff sein; (c) die bisherige Facette entfaellt ersatzlos. (c) konnte anders ausfallen (Facette uebergangsweise behalten), ohne (a) zu aendern -- es ist der Breaking Change mit eigener Negativfolge. Zwei.
- **ADR-37**: (a) Rolle wird eigene Ressource, Actor nur Traeger; (b) Spezifikationskanten enden bei der Rolle; (c) Besetzung als eigene optionale Aussage; (d) Rolle entsteht im Actor-Kontext, nicht daneben. (d) konnte anders ausfallen (eigener BC) -- steht als verworfene Option #5 im selben Record. Zwei.
- **ADR-45**: (a) Domaenentyp ist unveraenderlicher Record ohne Graph-Zugang; (b) kein Konstruktions-Out-Port; (c) Uebersetzung allein im Out-Adapter; (d) Rueckschreiben als Feld-Delta, nie als Objekt-Abzug; (e) nicht gefuehrte Tripel bewahrt der Adapter. (d) konnte anders ausfallen (Vollabzug mit Merge im Adapter), ohne (a)-(c) zu aendern. Zwei.
- **ADR-46**: (a) Zielschnitt ueber den ganzen Lifecycle; (b) acht benannte Kontexte plus Akteursidentitaet; (c) Begriffe der gebauten Software bleiben draussen; (d) Vorgehensmodell-Rituale bleiben draussen, Iteration methodenneutral; (e) Bauzustand nicht Teil der Entscheidung; (f) Kern ungebaut/ausserhalb; (g) gegen Normen geprueft. (c) und (d) sind zwei voneinander unabhaengige Ausschluesse, (b) eine dritte Aussage. Mindestens drei.
- **ADR-48**: (a) Komponenten bleiben, kein Kern verschmolzen; (b) jeder BC wird ein Maven-Parent (mit Zuordnung); (c) Parent hat keine technische Gestalt; (d) Komponente = Kern + ein Adaptermodul je Technologie. (a) und (b) sind unabhaengig -- verworfene Option #3 haelt (a) und verwirft (b). (d) ist eine dritte, eigenstaendig variierbare Modulregel. Drei.
- **ADR-49**: (a) ein Mechanismus fuer jede Kante; (b) eigener Out-Port ueber die Published Language des Nachbarn, beide Richtungen; (c) In-Port-Ergebnisse tragen fremde Codes; (d) kein Borrowed In-Port, keine Modulabhaengigkeit; (e) der Adapter liest nur, schreibt nie ins Nachbarmodell. (e) konnte anders ausfallen (Schreibrecht im Nachbargraph), ohne (a)-(d) zu aendern. Zwei (schwach).
- **ADR-51**: (a) Root traegt nur Verdrahtung; (b) Ausnahme: generischer Lesepfad samt Bericht, als Behelf; (c) Ausnahme an Typunabhaengigkeit gebunden; (d) kontextuebergreifende Auswertungen gehoeren nicht in den Root. (b) konnte anders zugeschnitten werden, ohne (a)/(d) zu aendern -- genau das tut ADR-55. Zwei; die Buendelung erzwingt eine Vollabloesung statt einer Korrektur.
- **ADR-52**: (a) sechs Bounded Contexts; (b) drei Kernkontexte mit Inhalten; (c) drei stuetzende Kontexte mit Inhalten; (d) Liste ist Bauzustand, kein Katalog. (c) ist bereits in ADR-36 (Akteur), ADR-53 (Registry) und ADR-54 (Analyse) entschieden; (b) und (c) sind unabhaengig voneinander. Zwei plus Dopplung.
- **ADR-53**: (a) Registry ist eigener stuetzender BC, stromaufwaerts, auf der Karte sichtbar; (b) sie besitzt Projekt, Anker, Sprachzusagen, Projektidentitaet; (c) Mandanten-/Nutzeridentitaet gehoert nicht dazu und bekommt spaeter eigenen Kontext; (d) ein Mandant besitzt Projekte. (c)/(d) konnten anders ausfallen, ohne (a)/(b) zu aendern, und entscheiden ueber etwas ausdruecklich Vertagtes. Zwei.
- **ADR-54**: (a) Modellanalyse ist eigener, rein lesender BC; (b) eine Komponente, deren Store-Adapter die Published Language jedes Kontexts liest; (c) Umfang: fuenf Auswertungen, Sprachpruefung, Bericht; (d) im Bericht sind Lesemodell und Darstellung getrennt; (e) Ueberblick/Rohzugriff gehoeren nicht dazu. (d) ist eine unabhaengige Aussage ueber den inneren Bau einer Komponente. Zwei.
- **ADR-55**: (a) Root traegt nur Verdrahtung (wortgleich mit ADR-51); (b) Ausnahme nur Ueberblick und Rohzugriff; (c) Bindung an Typunabhaengigkeit; (d) der Bericht gehoert zur Modellanalyse. Neu ist allein (b)/(d); (a) ist Status quo gegenueber dem geltenden ADR-51. Zwei, und (d) steht wortgleich schon in ADR-54.
- **ADR-56**: (a) es gibt einen Shared Kernel aller BCs; (b) sein Inhalt (fuenf Begriffe); (c) Sprache ist Modell; (d) Aufnahmeregel notwendig, nicht hinreichend; (e) jeder Kern haengt an genau diesem einen Modul. (b) und (d) sind unabhaengig (Regel uebernehmen, anders befuellen); (e) ist eine dritte Abhaengigkeitsregel. Drei.
- **ADR-57**: (a) Vokabularmodul je BC mit mehr als einer Komponente; (b) sein Inhalt; (c) konkrete Zuordnung; (d) Ein-Komponenten-Kontext bekommt keines; (e) der Verweistyp aufs Glossar bleibt je Kontext eine Kopie. (d) ist die Negativform von (a); (e) konnte anders ausfallen (in den Shared Kernel). Zwei (schwach).
- **ADR-58**: (a) kein API-Modul je Komponente; (b) In-Port-Schnittstellen bleiben im Kern, eine je Anwendungsfall; (c) Adaptersicht per Architekturregel; (d) Wiederaufnahme-Ausloeser bleibt der des Schemas. (b) enthaelt mit "eine Schnittstelle je Anwendungsfall" eine eigenstaendige Granularitaetsentscheidung; (c) konnte anders ausfallen (Konvention statt Architekturtest). Drei (schwach).
## 4. Requirements (11) -- Leser-Ebene

Regelquelle: Checkliste `Checklist per requirement` aus `/arknet:req-interview`.
Spalten: SOPHIST-Block (Pass = Passiv/fehlender Akteur, Nom = Nominalisierung,
Komp = unvollstaendiger Komparativ, Univ = Universalquantor, Proz = unvollstaendig
spezifiziertes Prozesswort/Bedingung), dann ISO 29148 (Voll, Eind, Kons, Test,
Rat, Abh, NF, Prio, Typ), dazu Loes (Loesungsfreiheit) und Herk (Bedarfstraeger-Rolle).
Leere Zelle = nicht geprueft (kommt nicht vor).

| | Pass | Nom | Komp | Univ | Proz | Voll | Eind | Kons | Test | Rat | Abh | NF | Prio | Typ | Lös | Herk |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **FR-1** | ok | ok | ok | ok | „testbare Beschreibung" – wer beurteilt Testbarkeit? | Felder rationale/Priorität/Herkunft fehlen; Default-Status liegt in FR-5 AC1 | „stellvertretend für" ist nicht beobachtbar | ok | 3 von 5 Behauptungen ohne AC | **fehlt** | FR-4, FR-5 ungenannt | keine (Nebenläufigkeit, Textgrenzen) | MUST plausibel | FR ok | AC2 nennt Codeformat FR-n/NFR-n (grenzwertig) | ROLE-1 nur als „stellvertretend", Ausführer ROLE-4 |
| **FR-2** | ok | ok | **„denselben Detailgrad" – Maßstab nirgends definiert** | „alle erfassten" abgrenzbar, ok | „verkürzte Kennung" – welche Verkürzungen zulässig? | Beschreibung nennt nur Business-Code, AC4+why regeln Kennungsformen | zwei Ressourcentypen × zwei Zugriffsarten in einem Requirement | **Beschreibung ⟂ AC4/rationale** | AC3+AC4 prüfen mehr als der Text zusagt | vorhanden, begründet aber die Kennungstoleranz, nicht das Requirement | FR-8 AC4 hängt am selben Begriff | Verhalten bei großem Bestand offen | MUST plausibel | FR ok | ok | nur ROLE-4 (Systemrolle) |
| **FR-3** | ok | ok | ok | ok | AC7 „gegen den neuen Stand erneut ausgeführt" – Wiederholungsgrenze/Abbruch offen | rationale nicht änderbar laut Text; einzelnes AC entfernen fehlt | „Verknüpfungen nicht antasten" ⟂ Werkzeug (`usesTermCodes`) | **Widerspruch zu FR-4 (kein Entkoppeln) und zum Werkzeugvertrag** | AC4+AC7 über den Text hinaus | **fehlt** | FR-1, FR-4 | Nebenläufigkeit nur als AC, keine NFR | SHOULD < FR-4 MUST fraglich | FR ok | **AC4 (Voll-Ersetzen) und AC7 (Retry) sind Mechanismus** | ROLE-1 stellvertretend |
| **FR-4** | ok | ok | ok | ok | „den sein Text verwendet" – wer prüft das? | kein Entfernen einer Verknüpfung; Constraint-Verknüpfung fehlt (FR-2/3 nennen sie) | ok | mit FR-3 (s.o.) | Qualifier + Zweckhalbsatz ohne AC | **fehlt** (Zweck steckt in der description) | FR-1, FR-3, FR-10 | – | MUST > FR-3 SHOULD fraglich | FR ok | ok | nur ROLE-4 |
| **FR-5** | ok | ok | ok | **AC6 „keine Konsequenz … keine Reihenfolge" – offene Negativ-Allaussage** | ok | Default-Status (AC1) gehört zu FR-1 | „Konsequenz" kollidiert mit TERM-30 Consequence, das FR-5 verlinkt | AC1 gehört fachlich zu FR-1 | AC6 nicht erschöpfend abnehmbar | vorhanden, trägt | FR-1 (AC1), FR-3 | – | COULD plausibel | FR ok (Statuswerte sind Modell, nicht Technik) | ok | ROLE-1 stellvertretend |
| **FR-6** | ok | ok | ok | AC2 „an jeder Stelle" – Prüfumfang offen | „vergleichbare Gruppe" – Abgrenzung offen (im why als Urteil ausgewiesen) | Umtypisieren bestehender Akteure fehlt | dt. Paraphrasen statt TERM-16/3/4/5; TERM-16 nicht verlinkt | ok | „vergleichbar" nicht abnehmbar, sonst ok | vorhanden, trägt | FR-7 | – | SHOULD plausibel | FR ok (Metamodell-Erweiterung) | ok | **keine Rolle genannt** |
| **FR-7** | ok | ok | ok | „ausschließlich auf Rollen" prüfbar, ok | **AC3 „Rolle, die üblicherweise kein eigenes Interesse trägt" – Rolle trägt keinen Typ; Bedingung nicht entscheidbar** | zweite Zusage (Lückenprüfung gegen erfasste Rollen) ohne AC | „Träger" = Actor (TERM-2), nicht verlinkt | **FR-1/FR-3 führen Herkunft nicht als Feld** | Zweckklausel unbelegt, AC3 unentscheidbar | vorhanden, trägt | FR-1, FR-3, FR-6 | – | SHOULD plausibel | FR ok | ok | **keine Rolle genannt – obwohl Herkunft das Thema ist** |
| **FR-8** | AC1 „wird erzeugt" – Erzeuger unbenannt | ok | **AC4 „demselben Detailgrad wie das gezielte Nachschlagen" – Maßstab undefiniert** | „jede Ressource" prüfbar, ok | Auslöser/Zeitpunkt der Erzeugung offen | nichts zu Aktualität/Veralterung der Lesefläche | „Modellbestand" ≠ TERM-12 Architekturmodell: zwei Wörter, eine Sache | an FR-2 gekoppelt | AC3 (Determinismus) ohne Entsprechung im Text | vorhanden, **argumentiert aber aus der Architekturentscheidung store-first** | FR-2 | – | SHOULD plausibel | **Grenzfall: Folge einer Architekturentscheidung** | **„Store", „kein Netzzugriff", „schreibgeschützt" = Mechanismus** | keine Rolle |
| **FR-9** | ok | „Audit", „Modulschnitt", „Zuständigkeit" nicht entfaltet | ok | **„nur dort", „Jeder Vorschlag", „für sich keine" – Gegenfall nicht gesucht** | „Sprachbruch" inline definiert, Entscheidung aber beim Nutzer (AC3) → Systemanteil unscharf | Verhalten bei null Kandidaten fehlt | „Sprachbruch" kein Term; „Akteure/Begriffe" statt TERM-2/13 | ok | AC2 nur durch Urteil falsifizierbar, nicht maschinell | vorhanden, trägt | FR-10, TERM-19 | – | SHOULD plausibel | **Verfahrensregel eines Audits, kein Systemverhalten → ADR/Methodenentscheidung prüfen** | ok | ROLE-3 naheliegend, nicht genannt |
| **FR-10** | ok | ok | ok | „unter jedem Sprachtag" prüfbar, ok | **„die Sprache, in der die Fachgemeinschaft den Begriff führt" – Feststellung durch wen?** | Erstanlage (welche Sprache zuerst) offen | ok | **eigene Regel im Requirement-Korpus selbst verletzt (dt. Paraphrasen engl. Labels)** | **Satz 2 ohne AC und nicht abnahmefähig** | vorhanden, trägt | FR-4, FR-9 | – | SHOULD plausibel | FR ok; Satz 2 ist Redaktionskonvention | ok | keine Rolle |
| **NFR-1** | „ablehnen" – Reaktion unbestimmt | ok | ok | „jeden HTTP-Aufruf", „Jeder Einstiegspunkt" – bei Aufzählung prüfbar | **„ablehnen" womit? Allowlist-Inhalt/Pflege offen; fehlender Origin-Header ungeregelt** | Protokollierung fehlt; Verhalten ohne Origin-Header fehlt | reine Lösungssprache, kein einziger Glossarbegriff | ok | AC4 (portunabhängig) ohne Entsprechung im Text | vorhanden, trägt | FR-8 (beide setzen den Daemon voraus) | ist selbst NF; `qualityCategory` im Lesepfad nicht sichtbar | MUST plausibel | NFR richtig (selbstgesetzt, kein externer Zwang → kein Constraint) | **schwer: Daemon/HTTP/Origin/Host/Allowlist/Loopback/MCP-Transport** | keine Rolle |

### 4.1 Belegsammlung -- Zerlegung der Beschreibungen

**FR-1** (4 Behauptungen)
- „ermöglicht Anlegen eines neuen Requirements" — abnahmefähig (Aufruf gelingt). AC2 belegt.
- „einem Coding AI Agent … stellvertretend für einen Requirements Engineer" — **nicht widerlegbar**: das System kann nicht beobachten, wer stellvertretend handelt. Kein AC.
- „funktional oder nicht-funktional" — abnahmefähig (beide Typen annehmen), **kein AC**.
- „mit Titel, testbarer Beschreibung und mindestens einem Akzeptanzkriterium" — Titel/AC abnahmefähig (AC1); „testbare Beschreibung" **nicht widerlegbar**, kein Prüfer benannt, kein AC.

**FR-2** (3 Behauptungen)
- „Nachschlagen anhand seines Business-Codes" — abnahmefähig, AC2.
- „oder alle erfassten Requirements bzw. Constraints auflisten" — abnahmefähig, AC1; „denselben Detailgrad" jedoch gegen einen nirgends fixierten Maßstab.
- Implizit über AC4/rationale: „verkürzte Kennungen werden angenommen, Mehrdeutigkeit wird gemeldet" — abnahmefähig, **steht aber nur im AC und im why, nicht im normativen Satz**. Der Text verspricht weniger als die Abnahme prüft.

**FR-3** (3 Behauptungen)
- „Titel, Beschreibung, Akzeptanzkriterien und Priorität ändern" — abnahmefähig; `rationale` fehlt in der Aufzählung.
- „ohne Business-Code und Status anzutasten" — abnahmefähig, AC5/AC6.
- „ohne die Verknüpfungen zu Terms und Constraints anzutasten" — abnahmefähig, AC5 — **aber inhaltlich falsch gegen den ausgelieferten Vertrag** (`req_update` besitzt `usesTermCodes`, das die Kanten ganz ersetzt).
- AC7 (Nebenläufigkeit/Retry) hat **keine Entsprechung im Beschreibungstext** und schreibt zusätzlich einen Mechanismus vor.

**FR-4** (3 Behauptungen)
- „Requirement mit einem Term verknüpfen" — abnahmefähig, AC4.
- „den sein Text verwendet" — **nicht widerlegbar**: keine Instanz prüft die tatsächliche Verwendung; kein AC.
- „damit nachvollziehbar wird, welche Begriffe ein Requirement in Anspruch nimmt" — Zweckklausel, gehört ins leere `rationale`-Feld; nicht abnahmefähig, kein AC.

**FR-5** (2 Behauptungen)
- „Status zwischen PROPOSED und ACCEPTED wechseln" — abnahmefähig, AC2/AC3.
- „stellvertretend für einen Requirements Engineer" — **nicht widerlegbar** (wie FR-1).
- AC1 (Default PROPOSED beim Anlegen) prüft eine Zusage von **FR-1**, nicht von FR-5. AC6 („keine Konsequenz, keine Kopplung, keine Reihenfolge") ist eine offene Negativ-Allaussage: eine Abnahme kann Einzelfälle, nie die Allaussage widerlegen.

**FR-6** (2 Behauptungen)
- „Gruppe ohne eigene Rechtsform als eigenständigen Akteur erfassen" — abnahmefähig, AC1.
- „ohne ihn einer Person oder Organisation mit Rechtsform zuzuschlagen" — abnahmefähig über den eigenen Typ (AC1); die Abgrenzung selbst („vergleichbare Gruppe") ist bewusst Urteil des Erfassenden, also nicht abnahmefähig — das steht immerhin im why.
- AC2 („an jeder Stelle verwendbar") prüft mehr als der Text verspricht und ist nur durch Aufzählung aller Stellen abnehmbar; derzeit existiert genau eine (`filledBy`).

**FR-7** (3 Behauptungen)
- „für ein Requirement festhalten, aus dem Bedarf welcher Rolle es stammt" — abnahmefähig, AC1.
- „damit sich prüfen lässt, ob ein Requirement einen realen Bedarf abbildet **und ob gegenüber den erfassten Rollen ein Bedarf fehlt**" — die zweite Hälfte fordert eine Lückenprüfung; **kein AC verlangt sie**. Nicht abnahmefähig in der jetzigen Fassung.
- „reine Verankerung; Interessen/Aushandlung/Konflikte werden nicht abgebildet" — Abgrenzung, nur als Abwesenheit prüfbar.
- AC3 („Rolle, die **üblicherweise** kein eigenes Interesse trägt, etwa eine Systemrolle") — **nicht entscheidbar**: eine Rolle trägt im Modell nur Name/Beschreibung/`filledBy`, kein Merkmal, an dem „Systemrolle" festzumachen wäre.

**FR-8** (3 Behauptungen)
- „Modellbestand eines Projekts als Lesefläche erzeugen" — abnahmefähig.
- „in sich geschlossen, schreibgeschützt, ohne laufenden Store zu öffnen" — abnahmefähig, AC1/AC2 — nennt aber den Mechanismus statt des Bedarfs.
- AC3 (Determinismus) und AC4 (Detailgrad) prüfen **zwei Zusagen, die der Beschreibungstext nicht macht**; AC4 zudem gegen den undefinierten Maßstab aus FR-2.

**FR-9** (4 Behauptungen)
- „Kontextgrenze nur dort vorschlagen, wo ein Sprachbruch liegt" — abnahmefähig nur, wenn „Sprachbruch" entscheidbar ist; AC3 legt die Entscheidung ausdrücklich beim Nutzer → die Systemzusage schrumpft auf „legt Beleg vor".
- „Jeder Vorschlag nennt den Sprachbruch als Beleg" — abnahmefähig, AC1.
- „Zuständigkeit, Modulschnitt und Datenmenge begründen für sich keine Kontextgrenze" — AC2; **nur durch menschliches Urteil falsifizierbar**, nicht maschinell.
- „liegt eine solche Beobachtung vor, wird sie als Beobachtung gemeldet" — abnahmefähig.

**FR-10** (3 Behauptungen)
- „Label unter jedem geführten Sprachtag mit demselben Wort" — abnahmefähig, AC1/AC2/AC4.
- „übersetzt wird allein die Definition" — abnahmefähig als Kehrseite.
- „Welches Wort das ist, entscheidet die Sprache, in der die Fachgemeinschaft den Begriff führt, nicht die Standardsprache des Projekts" — **nicht widerlegbar durch eine Abnahme**, kein AC; das ist eine Redaktionsregel für Menschen, kein Systemverhalten.
- AC3 (Rename gilt für alle Tags) — abnahmefähig, im Text nur implizit.

**NFR-1** (3 Behauptungen)
- „als langlebiger Daemon" — Voraussetzung, keine Anforderung; nicht abnahmefähig, weil nicht widerlegbar formuliert.
- „jeden HTTP-Aufruf ablehnen, dessen Origin-/Host-Angabe nicht auf der Allowlist steht" — abnahmefähig, AC1/AC2 — offen bleibt, **was „ablehnen" heißt** und **was auf der Allowlist steht**.
- „damit ein per DNS-Rebinding umgebogener Hostname nicht als same-origin gilt" — Zweckklausel, im `rationale` bereits ausgeführt; nicht abnahmefähig.
- AC4 („unabhängig vom Port angenommen") ist eine **eigenständige, im Text nicht angekündigte Zusage** und lockert das Sicherheitsversprechen um eine Dimension.
## 5. Use Cases (4) -- Leser-Ebene

Regelquelle: Checkliste `Checklist per use case` aus `/arknet:req-interview`.
Leere Zelle kommt nicht vor; alle vier wurden vollstaendig gelesen.

| UC | Trigger klar? | Rolle primär+supporting korrekt/existent? | Goal-in-context, 1 Satz, kein Wie? | Main flow lückenlos, je Schritt testbarer Zustandsübergang? | Extensions (leer/Teilausfall/Timeout/Abbruch)? | realises-Link auf existierendes FR/NFR? | Titel-Differenzierung ggü. realisiertem Req? | Vor-/Nachbedingung |
|---|---|---|---|---|---|---|---|---|
| **UC1** | Fund: "macht … relevant" ist kein beobachtbares Ereignis, kein handelnder Akteur benannt | Fund: primär ROLE-4 (Vermittler) allein, kein Mensch beteiligt — Bedarfsträger verdeckt; einziger UC ohne ROLE-1 | Fund: Ziel = Wissenszustand des Werkzeugs ("Agent kennt…"), Umschreibung der Systemfunktion Lesen/Auflisten | Fund: Schritt 1 bündelt zwei Abläufe ("gezielt **oder** als Liste"); Schritt 2 liegt ausserhalb der Systemgrenze, nicht beobachtbar, führt nicht weiter | Fund: Mehrdeutigkeits-Ablehnung aus FR-2-AK fehlt (Kernfall des FR-2-`why`); Store-Ausfall/Timeout fehlt. Leerer Bestand: ok | ok (Schritt 1 → FR-2); Fund: der Mehrdeutigkeitsteil von FR-2 hat keinen Schritt | Fund: "nachschlagen" vs. FR-2 "bereitstellen" — dasselbe Objekt, gespiegeltes Verb, kein breiteres Rollenziel | Fund: precondition fehlt ganz; postcondition ist Wissen im Aufrufer (nicht am System prüfbar) und sagt mehr zu (Status/Priorität/Terms) als Ziel, Schritt 1 und FR-2-AK |
| **UC2** | ok; Fund: an "Gespräch mit dem Coding AI Agent" gebunden — Lösungsweg im Trigger | ok (ROLE-1 primär, ROLE-4 supporting) — Wert fällt dem RE zu | Fund: Systemzustand ("ist vorhanden") statt Rollenwert; "wozu" fehlt. Teilweise gerettet durch "testbar" | Fund: Schritt 2 "prüft auf Duplikate/Konflikte" ohne Kriterium; Schritt 3 verbirgt eine ganze Interviewschleife ohne prüfbares Abbruchkriterium; sonst lückenlos 1-6 | Fund: nur 1 Extension. Es fehlen: Ablehnung ohne Akzeptanzkriterium (FR-1-AK!), Duplikat-Treffer aus Schritt 2, Term existiert noch nicht (FR-4-AK), RE-Abbruch, Schreibfehler | ok (5→FR-1, 6→FR-4); Fund: Schritt 6 setzt existierende Terms voraus, kein UC beschreibt "Term erfassen" | Fund: "erfassen" vs. FR-1 "anlegen" — Synonym, Systemoperation statt Rollenziel | Fund: precondition "stehen im Dialog" ist kein prüfbarer Zustand und schreibt den Lösungsweg fest; postcondition prüfbar, aber greift "testbar" (Ziel) und den Anfangsstatus PROPOSED nicht auf |
| **UC3** | Fund: "zeigt sich, dass …" — Passiv ohne Akteur (SOPHIST); an "Dialog" gebunden | ok (ROLE-1 primär, ROLE-4 supporting) | Fund: zwei Sachen in einem Satz — Rollenwert **plus** Systemgarantie ("ohne Identität zu verlieren"), letztere ist wörtlich FR-3-AK | Fund: Schritt 3 wie UC2 (Schleife ohne Abbruchkriterium); sonst lückenlos 1-6 | Beste Abdeckung im Set (3 Fälle, treffen FR-3-AK). Fund: FR-3-AK "mitgegebene AK ersetzen die bestehenden vollständig" (Datenverlustfall) fehlt; RE-Abbruch fehlt; Extension 3 beschreibt Systemverhalten statt Ablauffortsetzung (Auto-Retry ohne Rückfrage, obwohl der fremde Stand die in Schritt 4 bestätigte Fassung überholt haben kann) | ok (5→FR-3, 6→FR-4); Fund: **Entfernen** einer nach der Korrektur nicht mehr benutzten Term-Kante kommt weder im Schritt noch im Requirement-Set vor | Fund: "korrigieren" vs. FR-3 "nachträglich ändern" — Systemoperation | precondition prüfbar (ok). Fund: postcondition schweigt zum Status, obwohl FR-3 ihn ausdrücklich zusichert und UC4 den Status zum Thema macht (bleibt ein ACCEPTED-Requirement nach Korrektur ACCEPTED?) |
| **UC4** | ok — Akteur und Anlass benannt | Fund: Nutzniesser sind laut Ziel "Mitlesende" — im Rollenregister nicht vorhanden, weder primär noch supporting; ROLE-2/ROLE-3 wären die plausiblen Mitlesenden und kommen nirgends vor | Bestes Ziel im Set (Wirkung statt Operation). Fund: es verspricht "inhaltlich abgestimmt", während FR-5 den Status ausdrücklich als **unverbindliches Signal ohne Durchsetzung** definiert — das Ziel behauptet mehr als das realisierte Requirement trägt | ok — 4 Interaktionsschritte, lückenlos, jeder führt weiter (bestes Schrittprofil im Set) | Fund: Extension 3 ("erweist sich später als verfrüht") ist ein eigener Ablauf mit eigenem Trigger, kein Zweig dieses Ablaufs; die echte Abweichung (RE widerruft in Schritt 3) fehlt; Store-Ausfall fehlt | ok (4→FR-5) | ok — einziger UC, dessen Titel eine Rollenhandlung statt einer Feldoperation nennt. Kleiner Fund: "akzeptieren" deckt nur eine Richtung, UC-Extension und FR-5 führen beide | precondition prüfbar (ok). Fund: postcondition passt zu FR-5, aber nicht zum Ziel (Statuswert vs. Erkennbarkeit) und widerspricht Extension 3, die auf PROPOSED endet |

### 5.1 Belegsammlung -- Zerlegung von Ziel und Schrittfolge

**UC1**
- Ziel wörtlich: "Der **Coding AI Agent kennt** Titel und Beschreibung …, **um sie in seiner laufenden Arbeit zu verwenden**."
- Wertempfänger = ROLE-4. Laut `role_list` ist ROLE-4 definiert als Rolle, die "**zwischen** einem Requirements Engineer, Architect oder DDD Practitioner **und arknet vermittelt**". Ein Vermittler kann per Definition kein Endziel tragen — der Wert entsteht erst beim Menschen. Das Ziel endet aber beim Vermittler.
- "kennt X" ist ein Zwischenzustand, kein Ergebnis von Wert. Was der Mensch davon hat, steht nirgends.
- UC1 wird von UC2 (Schritt 2), UC3 (Schritt 2) und UC4 (Schritt 2) eingebunden → faktisch ein Subfunction-Level-UC, aber ohne eigenen menschlichen Anlass und ohne Kennzeichnung als solcher.
- Schritte: (1) lesen — Interaktionsschritt, aber ein Lesevorgang ändert keinen Systemzustand, die Nachbedingung ist deshalb nur am Aufrufer prüfbar; das "oder alle als Liste" ist ein zweiter Ablauf in derselben Zeile. (2) "verwendet … in seiner aktuellen Aufgabe" — ausserhalb, nicht beobachtbar, führt den Ablauf nicht weiter; ohne gesetztes `scope` lässt sich nicht einmal begründen, warum er trotzdem dasteht.

**UC2**
- Ziel wörtlich: "Ein neues, testbares Requirement **ist im Architekturmodell vorhanden**." — Zustandssatz über den Store. Kein Rollenname, kein Nutzen. Nahe an FR-1 "Requirement anlegen". Der Zusatz "testbar" ist der einzige Teil, der aus dem Dialog stammt und nicht aus der Speicheroperation; er wird in der Nachbedingung wieder fallen gelassen.
- Wertempfänger korrekt ROLE-1. Das eigentlich Wertvolle (das im Dialog geschärfte, geteilte Verständnis) wird auf den Eintrag reduziert.
- Schritte: 1 RE beschreibt (Interaktion, ok) → 2 Agent prüft gegen Bestand (Interaktion + Include auf UC1; Prüfkriterium unbestimmt, und ein Treffer hat keine Fortsetzung) → 3 Agent befragt "bis … feststehen" (verbirgt die gesamte Elicitation in einem Schritt) → 4 RE bestätigt (prüfbar, ok) → 5 Anlegen (Zustandsübergang, ok) → 6 Term-Verknüpfung (Zustandsübergang, ok, aber setzt existierende Terms voraus). Trigger→Nachbedingung geschlossen.

**UC3**
- Ziel wörtlich: "Ein bereits erfasstes Requirement **gibt das inzwischen geschärfte Verständnis wieder**, **ohne seine Identität … zu verlieren**." Satzhälfte 1 = echter Rollenwert (Modell und Verständnis wieder deckungsgleich). Satzhälfte 2 = Mittel/Systemgarantie, wörtlich FR-3-AK ("Nach einer Änderung trägt das Requirement denselben Business-Code wie zuvor").
- Schritte: 1 benennen (ok) → 2 lesen via UC1 (ok) → 3 befragen bis Fassung steht (Schleife) → 4 bestätigen (ok) → 5 zurückschreiben (Zustandsübergang, ok) → 6 neue Terms verknüpfen (ok). Geschlossen. Nicht abgedeckt: die Umkehrung von 6 — Terms, die durch die Korrektur aus dem Text verschwinden, behalten ihre Kante.

**UC4**
- Ziel wörtlich: "**Für Mitlesende ist am Requirement erkennbar**, dass es nicht mehr im Entwurf steht, sondern **inhaltlich abgestimmt** ist." Wertempfänger explizit ein Dritter, der nicht modelliert ist. Formal das beste Ziel (Wirkung statt Operation) und zugleich das einzige, das über sein Requirement hinausgreift: FR-5 sichert ausdrücklich zu, dass ein Statuswechsel "keine Konsequenz auslöst … keine Validierungsregel" — "inhaltlich abgestimmt" ist damit eine Behauptung ohne Deckung.
- Schritte: 1 benennen → 2 vorlegen (via UC1) → 3 bestätigen → 4 Status setzen. Jeder Schritt ein Interaktionsschritt, jeder führt weiter, keiner ist eine Implementierungsanweisung. Sauberste Schrittfolge des Sets.
## 6. Glossarbegriffe (27) -- Leser-Ebene

Regelquelle: `Language`, `Glossary terms` und `Checklist per glossary term` aus
`/arknet:req-interview`. Alle 27 Terms wurden in beiden Sprachen gelesen.
`ok` = geprueft und in Ordnung. Leere Zelle kommt nicht vor.

| Term | A Eindeutig i. Glossar | B Ggü. externer Bedeutung | C Definition präzise | D Actor/Role statt Term | E Ontologie-Abgleich | F Doppelung | G Impl.-frei | H ADR-frei | I Config-frei | J Zirkularität | K Undef. tragende Wörter | L Label-Regel | M broader/related |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| TERM-1 Metamodel | ok | ok (MDA/MOF deckt sich) | ok | n.a. | keine Klasse, ok (Metaebene) | ok | **"mit arknet erstellten Modell" nennt das Produkt** | ok | ok | **2er-Zirkel mit TERM-12** | "Modell", "Klassen", "Regeln" | ok | ok |
| TERM-2 Actor | ok (klar gg. TERM-21) | **Kollision: UML/Jacobson nennt "Actor" genau die Rolle; hier Träger** (in Def. adressiert) | ok, Gattung "jemand oder etwas" sehr blass | ok (Metamodell-Klasse, kein konkreter Actor) | `arkproc:Actor` + 4 Subklassen ok | ok | ok | ok | ok | kein Zirkel (Gegenbegriff-Paar mit TERM-21) | **"System"** | ok | ok |
| TERM-3 Human Actor | ok | ok | ok | ok | `HumanActor` ok | ok | ok | ok | ok | ok | "natürliche Person" (extern etabliert, ok) | ok | related TERM-5 **redundant zur gemeinsamen broader-Kante**; TERM-16 fehlt |
| TERM-4 System Actor | ok | ok | ok, aber **"extern" ohne Bezugspunkt** | ok | `SystemActor` ok | ok | ok | ok | ok | ok | "System", "Dienst" | ok | wie TERM-3: Geschwister-related unvollständig (kein TERM-16) |
| TERM-5 Legal Actor | ok | ok | ok | ok | `LegalActor` ok | **zählt Abgrenzung gg. TERM-3/4 erneut auf statt zu verweisen** | ok | ok | ok | ok | "juristische Person" (extern, ok) | ok | related 3,4 aber **nicht 16** |
| TERM-10 Requirement | ok | ok (ISO 29148 deckt sich) | ok | n.a. | `Requirement` ok | ok | ok | ok | ok | ok | **"System"**, "Qualitätseigenschaft" | ok | **kein related TERM-27 (Use Case realisiert Requirements)**; kein broader TERM-14 obwohl Ressource |
| TERM-11 Constraint | ok | **breit als "jede Einschränkung/Invariante" gelesen; lokal eng "extern vorgegeben"** | ok | n.a. | `Constraint` + 3 Subtypen ok | ok | ok | ok | ok | ok | "Lösungsraum" | ok | **zu eng: nur "für ein Requirement", das Modell kennt `uc_link_constraint`**; kein broader TERM-14 |
| TERM-12 Architecture Model | ok | ok | ok | n.a. | keine Klasse, ok | ok | **"mit arknet erfassten"** | ok | ok | **Zirkel mit TERM-1 und mit TERM-14** | "Ressource"→TERM-14 ok | ok | ok |
| TERM-13 Term | **"Term" vs. "Begriff" (TERM-19/25) — dasselbe Konzept, zwei Wörter** | ok (SKOS sagt "Concept") | ok | n.a. | `skos:Concept` ok | **Eindeutigkeitsaussage doppelt zu TERM-25** | ok | **"geführt im Glossar … auf den andere Ressourcen verweisen können" = Systemverhalten** | ok | ok | **"Glossar"** (tragend, kein Term) | ok | kein broader TERM-14 |
| TERM-14 Resource | ok | **stark vorbelegt (RDF/REST: alles mit IRI); lokal enger** | ok | n.a. | keine eigene Klasse, ok | ok | ok | ok | ok | **Zirkel mit TERM-12** | ok | ok | **ist broader von 29/30/32, aber nicht von 10,11,13,19,20,27,28 — inkonsistent** |
| TERM-15 Business Code | ok | ok | ok, aber 2. Hälfte beschreibt **Verwendung** statt Wesen | n.a. | `dcterms:identifier` ok | ok | ok | Eindeutigkeitsregel liegt in TERM-17 statt hier | ok | ok | "Kennung" ok | ok | ok |
| TERM-16 Group Actor | ok | ok | ok | ok | `GroupActor` ok | **zählt Abgrenzung gg. Human/Legal erneut auf** | ok | ok | ok | ok | "Rechtsform" | ok | **kein related zu 3/4/5, obwohl die untereinander related tragen** |
| TERM-17 Project | ok | ok (PM-Sinn ausdrücklich ausgeschlossen — vorbildlich) | ok | n.a. | `arkprj:Project` ok | ok | **"mit arknet beschrieben"** | **"innerhalb derer … Business-Codes eindeutig sind" = Systemverhalten/Mandantengrenze** | ok | ok | "System-of-Interest", "Library" | ok | related 12,13,18,23,33; **keine Kante zu TERM-19 (BC liegt im Projekt)** |
| TERM-18 Anchor | ok | ok | ok | n.a. | `arkprj:Anchor` + AnchorType ok | ok | **"ein Verzeichnis, eine Repository-Adresse" = Technik in der Definition** | Grenzfall: der Begriff ist eine Auflösungsmechanik (ADR-Stoff), keine Fachbedeutung | ok | ok | **"Arbeitsort"** | ok | ok |
| TERM-19 Bounded Context | ok | ok (DDD-Standard) | ok | n.a. | `arkddd:BoundedContext` ok | **Grenzrelativität doppelt zu TERM-25** | ok | ok | ok | ok | **"Domänenmodell"; "Domäne" fehlt als Term ganz** | ok | kein broader TERM-14 |
| TERM-20 Revision | ok | ok | ok | n.a. | `arkprov:Revision` ok | ok | ok | Grenzfall: Head-Pointer/Revisionsfolge ist Persistenz-Entscheidung | ok | **Selbstbezug: "die Revision, auf die er folgt"** (rekursiv, vertretbar) | "Änderungsfolge" | ok | kein broader TERM-14 |
| TERM-21 Role | ok | ok | ok | ok (Metamodell-Klasse) | `arkproc:Role` ok | ok | ok | ok | ok | kein Zirkel (Gegenbegriff zu TERM-2) | **Sprachbruch: "Anforderung" statt "Requirement"** | ok | ok |
| TERM-23 Dataset | ok | **RDF-Dataset (Named Graphs) stark vorbelegt** | ok, Gattung "Speichereinheit" ist aber technisch | n.a. | keine Klasse, ok | ok | **"Speichereinheit", "Abfrage" = Speicher-/Query-Technik** | **"genau ein reserviertes Dataset … Projekt-Registry" = Architekturentscheidung in einer Bedeutungsdefinition** | ok | ok | **"Projekt-Registry", "Abfrage"** | ok | ok |
| TERM-25 Ubiquitous Language | ok | ok (DDD-Standard) | ok | n.a. | keine Klasse, ok | **doppelt zu TERM-19 (Grenze) und TERM-13 (Eindeutigkeit)** | ok | ok | ok | ok | "Code", "Modell" | ok | ok |
| TERM-26 Component | ok | **C4/UML/Maven-"Komponente" mehrdeutig, keine Auflösung** | **FÄLLT DURCH: keine Gattung. Satz 1 ist eine Beziehungsaussage, Satz 2 Merkmale ohne Oberbegriff** | n.a. | **keine Klasse in der Ontologie** (Bausteinsicht-Begriff) | ok | **"hinter einer Schnittstelle gruppiert"** | dito = Design-Entscheidung | ok | ok | **"Schnittstelle", "Funktionalität", "Zuständigkeit"** | ok | related 19,25; kein broader |
| TERM-27 Use Case | ok | ok (Cockburn) | ok | n.a. | `UseCase` ok | ok | ok | ok | ok | ok | "Schritt", "Vor-/Nachbedingung", "Erweiterung", "System" | ok (`en`-prefLabel fehlt, bekannt) | **nur related TERM-21; fehlen 10 (realises), 11 (`uc_link_constraint`), 13 (`uc_link_term`)**; kein broader TERM-14 |
| TERM-28 Acceptance Criterion | ok | ok | ok | n.a. | `AcceptanceCriterion` ok | **Überschneidung mit TERM-10: beide über "testbar" definiert, die Abgrenzung bleibt implizit** | ok | ok | ok | ok | ok | ok (`en`-prefLabel fehlt, bekannt) | kein broader TERM-14, obwohl eigene Ressource (#266) |
| TERM-29 ADR | ok | ok (Nygard-Standard) | ok, Gattung ist aber "Ressource" (Speicherform) statt "Entscheidungsdokument" | n.a. | `ArchitectureDecisionRecord` ok | **zählt Folgen (TERM-30) und erwogene Optionen (TERM-31) auf, statt zu verweisen — genau das verbotene Re-Enumerieren** | ok | **"eigene, dauerhafte Ressource" = Speicherform in der Bedeutung** | ok | ok | ok | ok (`en`-prefLabel fehlt, bekannt) | broader 14 ok; **kein related zu 30 und 31** |
| TERM-30 Consequence | ok | ok | ok | n.a. | `Consequence` + `ConsequenceType` ok | ok | ok | **"als eigene, in der Entscheidung positionierte Ressource festgehalten" = Modellierungsentscheidung** | ok | ok | **"Architekturentscheidung"** (kein Term; nur ihr *Record* ist einer) | ok (`en`-prefLabel fehlt, bekannt) | broader 14; **kein related TERM-29** |
| TERM-31 Considered Option | ok | ok | ok | n.a. | `ConsideredOption` + `OptionOutcome` ok | ok | ok | ok | ok | ok | **"Architekturentscheidung"** | ok (`en`-prefLabel fehlt, bekannt) | **einziger Term ganz ohne Kante — broader TERM-14 und related TERM-29 fehlen** (deckt orphan_check) |
| TERM-32 Context Relationship | ok | ok | ok | n.a. | `ContextRelationship` + `RelationshipType` ok | Typ-Aufzählung dupliziert die Ontologie-Individuen ("etwa" mildert) | ok | ok | ok | ok | **"Context Map"** (tragend, kein Term), "Beziehungstyp" | ok (`en`-prefLabel fehlt, bekannt) | ok |
| TERM-33 Subdomain | ok | ok (DDD-Standard) | ok | n.a. | `arkddd:Subdomain` ok, **aber die Typen heißen dort "Core/Supporting/Generic *Domain*", nicht "…Subdomain"** | ok | ok | ok | ok | ok | **"Domäne/Gesamtdomäne" — der Oberbegriff, gegen den definiert wird, ist selbst kein Term** | ok (`en`-prefLabel fehlt, bekannt) | related 17,19 ok; **broader TERM-"Domain" fehlt, weil der Term fehlt** |

### 6.1 Belegsammlung: Gattung / Abgrenzung

| Term | Gattung | Unterscheidendes Merkmal |
|---|---|---|
| TERM-1 | formale Struktur (Klassen, Beziehungen, Regeln) | legt fest, was in einem Modell ausdrückbar ist |
| TERM-2 | jemand oder etwas (Platzhalter-Gattung) | kann einwirken / Interesse haben; Träger einer Rolle, nicht die Rolle |
| TERM-3 | Actor | ist eine natürliche Person |
| TERM-4 | Actor | ist ein externes System/ein externer Dienst |
| TERM-5 | Actor | ist eine juristische Person (Träger von Rechten/Pflichten) |
| TERM-10 | testbare Aussage | über Verhalten/Qualitätseigenschaft, die ein System erfüllen muss |
| TERM-11 | Randbedingung | nicht verhandelbar, extern vorgegeben, schränkt Lösungsraum ein |
| TERM-12 | Gesamtheit der Ressourcen eines Projekts | entspricht einem Metamodell |
| TERM-13 | Begriff der Ubiquitous Language eines BC | eindeutig definiert, im Glossar geführt, referenzierbar |
| TERM-14 | Element eines Architekturmodells | einzeln benennbar und ansprechbar, artunabhängig |
| TERM-15 | Kennung | kurz, menschenles-/tippbar, adressiert eine Ressource |
| TERM-16 | Actor | ohne eigene Rechtsform, handelt kollektiv |
| TERM-17 | benannter Gegenstand | dessen Architektur beschrieben wird; Grenze der Zugehörigkeit/Code-Eindeutigkeit; nicht das PM-Vorhaben |
| TERM-18 | Erkennungsmerkmal | für ein Projekt hinterlegt; n Anker : 1 Projekt |
| TERM-19 | ausdrücklich gezogene Grenze | innerhalb derer Domänenmodell und Begriffe einheitlich gelten |
| TERM-20 | einzelner unveränderlicher Stand einer Ressource | mit Zeitpunkt und Vorgängerrevision |
| TERM-21 | benannte Funktion | unabhängig von der Besetzung; darf unbesetzt bleiben |
| TERM-23 | Speichereinheit | hält Ressourcen+Revisionen genau eines Projekts; Abfragegrenze |
| TERM-25 | die eine gemeinsame Sprache von Fachleuten und Entwicklern | unverändert in Gespräch/Modell/Code; an ihren BC gebunden |
| TERM-26 | **keine** | (nur Merkmale: eigene Zuständigkeit, Funktionalität hinter Schnittstelle) |
| TERM-27 | geordnete Folge von Schritten | mit Vor-/Nachbedingung; Rolle erreicht ein wertvolles Ziel |
| TERM-28 | testbare Bedingung | muss gelten, damit ein Requirement als eingelöst zählt |
| TERM-29 | eigene, dauerhafte Ressource | hält *eine* architektonisch bedeutsame Entscheidung fest |
| TERM-30 | Folge (positiv/negativ/neutral) | zieht aus einer Architekturentscheidung; positionierte Ressource |
| TERM-31 | erwogene Alternative | mit Ausgang (gewählt/verworfen) und Begründung |
| TERM-32 | benannte Beziehung zwischen zwei BCs | nach Context-Map-Muster; eigene Ressource mit Typ |
| TERM-33 | fachlicher Teilbereich der Gesamtdomäne | unabhängig von der BC-Grenze; strategisch klassifiziert |
## 7. Bounded Contexts (6) und Context Relationships (13) -- Leser-Ebene

**Einschraenkung vorweg (gilt fuer diesen ganzen Abschnitt):** `/arknet:bc-audit`
ist ein Verfahren, um Kontext-**Kandidaten** im Requirements-/Use-Case-/Glossar-
Bestand zu finden, nicht um eine bereits eingetragene Grenze nachtraeglich zu
pruefen. `/arknet:context-map` hat fuer eine eingetragene Beziehung ebenfalls
keinen Pruefmodus -- es elicitiert, es verifiziert nicht. Jedes Urteil unten zu
einem **bereits eingetragenen** Kontext oder einer eingetragenen Beziehung ist
darum ein **Leserbefund ohne dahinterstehenden Pruefmodus**, keine
Regelanwendung. Regelanwendung im engeren Sinn ist allein der Kandidaten-Pass
(7.3).

### 7.1 Kontexte

| BC | Grenze am Material belegbar | Sprachbruch nachweisbar | Begriffskanten vollständig | Subdomain plausibel | Eingetragene Beziehungen plausibel |
|---|---|---|---|---|---|
| BC-1 Produkt & Anforderungen | nur als Ressourcentyp: 11 Requirements, 4 UCs, TERM-10/11 liegen vor, aber FR-6/FR-9/FR-10 handeln von BC-4- bzw. BC-2-Gegenständen und sind trotzdem BC-1-Ressourcen — das Material trennt "gehört zu BC-1" nicht von "handelt von BC-1" | schwach: `Konsequenz` in FR-5 vs. TERM-30 `Consequence` (BC-3), siehe 7.3.1 | nein: TERM-27 (Use Case) und TERM-28 (Acceptance Criterion) sind von der domainVision ausdrücklich beansprucht, tragen aber keine Kante | CORE bei 3 von 6 CORE — keine Aussage im Store belegt das strategische Gewicht (TERM-33) | Typ durchweg PUBLISHED_LANGUAGE, siehe 7.2 |
| BC-2 Domänenmodellierung | teilweise: TERM-13/19/25 + FR-9/FR-10 liegen vor; die Grenze zu BC-1 ist am Material nicht sichtbar (kein Begriff wechselt dort die Regel) | nein | nein: TERM-32 (Context Relationship) von der domainVision beansprucht, ohne Kante; ebenso TERM-26 (Component), TERM-33 (Subdomain) | CORE plausibel (das Führen von Sprache und Grenzen ist arknets eigentliches Angebot) | s. 7.2 |
| BC-3 Architektur & Entscheidungen | **nein**: null Begriffskanten, null Requirements, null Use Cases über ADRs; die 30 ADRs sind Instanzen, kein Sprachmaterial. Die Grenze ist nur durch die domainVision behauptet | nur als Gegenseite von 7.3.1 | **nein, gar keine**: TERM-29/30/31 (ADR, Consequence, Considered Option) sind definiert und keinem Kontext zugeordnet | CORE — dieselbe fehlende Begründung wie BC-1 | s. 7.2; fehlende Beziehung zu BC-4 |
| BC-4 Akteur | halb: 4 Rollen + FR-6/FR-7 belegen die Rollen-Hälfte; die Actor-Hälfte hat **0 Actors** — TERM-2/3/4/5/16 sind definiert, nie instanziiert | nein | ok gegenüber der eigenen domainVision (6 Kanten, Actor-Taxonomie + Role vollständig) | SUPPORTING plausibel | s. 7.2; fehlende Beziehungen zu BC-2 und BC-3 |
| BC-5 Projekt-Registry | ja, am klarsten: TERM-17/18 sind trennscharf definiert, TERM-17 grenzt sich sogar ausdrücklich gegen den PM-Projektbegriff ab | nein (die Abgrenzung in TERM-17 ist eine Definitions-Disambiguierung, kein Bruch zwischen zwei Kontexten des Stores) | nein: TERM-23 (Dataset) nennt die Projekt-Registry in der eigenen Definition, trägt aber keine Kante | SUPPORTING plausibel; ein Argument für GENERIC (reine Auflösungs-Infrastruktur) wäre am Material genauso tragfähig | s. 7.2 |
| BC-6 Modellanalyse | **nein**: null Begriffskanten, null Requirements, null Use Cases. Als "lesende Auswertung über Kontextgrenzen hinweg" beschreibt die domainVision einen Zugriffsweg, keine Sprachgrenze | nein — per domainVision spricht BC-6 ausdrücklich **die Sprachen der anderen**, das ist das Gegenteil eines Sprachbruchs | **nein, gar keine** (orphan_check-Befund zu TERM-21/TERM-13 bereits bekannt, hier nicht wiederholt) | SUPPORTING — Leserzweifel: Auswirkungs-/Lücken-Analyse liegt näher an arknets Wertversprechen als die Requirement-CRUD in BC-1, die CORE trägt | s. 7.2 |

**Was "keine Begriffskante" fuer BC-3 und BC-6 bedeutet:** Ein Bounded Context ist per TERM-19 die Grenze, innerhalb derer ein Domänenmodell *und die Begriffe, die es benennt* einheitlich gelten. Ein Kontext ohne einen einzigen Begriff hat im Store nichts, worauf diese Grenze angewandt wäre. Seine Existenz ist dann eine Behauptung in der domainVision, die kein anderes Material stützt — nicht falsch, aber unbelegbar. Für BC-3 ist das besonders auffällig, weil die drei Begriffe, die er beanspruchen müsste (TERM-29/30/31), im Glossar existieren und kontextlos herumliegen; für BC-6 ist es strukturell, weil ein reiner Lesepfad definitionsgemäß keine eigene Sprache führt — was eher gegen den Kontext als für die fehlenden Kanten spricht.

**Querschnittsbefund zur Deckung:** 14 der 27 Begriffe tragen **keine** Kontextkante: TERM-1 (Metamodel), TERM-12 (Architecture Model), TERM-14 (Resource), TERM-15 (Business Code), TERM-20 (Revision), TERM-23 (Dataset), TERM-26 (Component), TERM-27, TERM-28, TERM-29, TERM-30, TERM-31, TERM-32, TERM-33. Die ersten fünf sind genau die querschnittlichen: TERM-15 kookkurriert in 9 von 21 Paaren, TERM-12 in 5. Das sind keine vergessenen Kanten, sondern das gemeinsame Vokabular aller Kontexte — im DDD-Vokabular ein Shared-Kernel- oder Published-Language-Bestand. Der Store hat dafür keine Ausdrucksform (`bc_link_term` würde denselben Begriff an sechs Kontexte hängen und die Aussage "gemeinsam" damit gerade verlieren).

### 7.2 Eingetragene Beziehungen

Alle 13 eingetragenen Beziehungen tragen PUBLISHED_LANGUAGE. Von 15 moeglichen Paaren fehlen zwei.

| Paar (upstream → downstream) | Eingetragen | Leserbefund (kein Prüfmodus) |
|---|---|---|
| BC-5 → BC-1 | PUBLISHED_LANGUAGE | Der Downstream übernimmt die Projekt-/Anker-Identität unverändert und hat keinen Hebel auf sie → **CONFORMIST** trifft die Lage genauer; für die Upstream-Seite wäre OPEN_HOST_SERVICE die Beschreibung (eine Auflösungsleistung für alle). Gegen PL spricht nicht die Sache, sondern dass PL hier nichts unterscheidet |
| BC-5 → BC-2 | PUBLISHED_LANGUAGE | wie BC-5 → BC-1 |
| BC-5 → BC-3 | PUBLISHED_LANGUAGE | wie BC-5 → BC-1 |
| BC-5 → BC-4 | PUBLISHED_LANGUAGE | wie BC-5 → BC-1 |
| BC-5 → BC-6 | PUBLISHED_LANGUAGE | wie BC-5 → BC-1 |
| BC-2 → BC-1 | PUBLISHED_LANGUAGE | Requirements verweisen per `usesTerm` auf das Glossar und passen sich dessen Begriffen an → **CONFORMIST**; **CUSTOMER_SUPPLIER**, falls BC-2 die Bedarfe von BC-1 tatsächlich einplant — das entscheidet der Autor, das Material sagt es nicht |
| BC-2 → BC-3 | PUBLISHED_LANGUAGE | Zwei gegenläufige Abhängigkeiten: ADRs verweisen auf Kontexte und Begriffe, während arknets eigene Kontextgrenzen ihrerseits per ADR entschieden werden. Das ist eher **PARTNERSHIP** (beide gelingen oder scheitern zusammen) als eine einseitige Veröffentlichung. Zugleich ein Indiz für CONFORMIST statt CUSTOMER_SUPPLIER: BC-3 bekam für `usesTerm` eine eigene Property, statt die geteilte Domain zu erweitern — BC-3 gestaltet BC-2s Modell also nicht mit |
| BC-2 → BC-6 | PUBLISHED_LANGUAGE | BC-6 liest und übersetzt nichts → **CONFORMIST**. ANTICORRUPTION_LAYER wäre die Alternative, wenn BC-6 übersetzte; laut eigener domainVision tut es das nicht |
| BC-1 → BC-3 | PUBLISHED_LANGUAGE | ADRs verweisen auf Requirements, nicht umgekehrt → **CONFORMIST**/**CUSTOMER_SUPPLIER** |
| BC-1 → BC-6 | PUBLISHED_LANGUAGE | wie BC-2 → BC-6 |
| BC-4 → BC-1 | PUBLISHED_LANGUAGE | Use Cases und FR-7 lösen Rollen per `ROLE-N` auf und nehmen BC-4s Modell wie es ist → **CONFORMIST** |
| BC-4 → BC-6 | PUBLISHED_LANGUAGE | wie BC-2 → BC-6 |
| BC-3 → BC-6 | PUBLISHED_LANGUAGE | wie BC-2 → BC-6 |
| *BC-2 → BC-4* | **fehlt** | Inkonsistenz: BC-4s sechs Begriffe liegen im selben Glossar wie die von BC-1 und BC-3, für die die Kante zu BC-2 gezogen ist. Entweder fehlt sie hier, oder die anderen drei sind zu viel |
| *BC-3 ↔ BC-4* | **fehlt** | Am Material korrekt: ADRs haben Kanten zu Requirements, Kontexten und Begriffen, keine zu Rollen oder Akteuren. Wenn die Map vollständig sein soll, wäre **SEPARATE_WAYS** die ehrliche Eintragung; nicht-eingetragen und SEPARATE_WAYS sind im Store aber ununterscheidbar |

**Zum Gesamtbild der Typwahl:** Der Einwand ist nicht, dass PUBLISHED_LANGUAGE bei einem einzelnen Paar falsch wäre — arknets RDF-Ontologien unter `w3id.org/arknet/*` sind mit ihren Labels und Kommentaren tatsächlich eine veröffentlichte Sprache, und das lässt sich für jede Kante anführen. Genau das ist das Problem: das Argument gilt für alle 13 Kanten gleichermaßen, weil es das gemeinsame RDF-Substrat beschreibt, nicht die Beziehung zwischen zwei Kontexten. Ein Beziehungstyp, der überall derselbe ist, unterscheidet nichts und trägt keine Information — die Context Map ist dann eine Erreichbarkeitsmatrix mit einem Typ-Etikett. Der wiederkehrende Kandidat ist CONFORMIST: an fast jeder Kante passt sich der Downstream einem Modell an, auf das er keinen Einfluss hat. Ob das die gewollte Lage ist, ist eine Autorenentscheidung — hier steht nur, dass die eingetragene Uniformität keine erkennbare Unterscheidung leistet.

Ergaenzend, ausserhalb des Store-Materials und daher schwaecher: ProjectId, ResourceId und DisplayLocale liegen in `arknet-shared-kernel`, also in geteiltem Code. Für das *Ziehen* einer Grenze wäre Modulschnitt kein Argument (FR-9); für die *Typisierung* einer bestehenden Beziehung ist geteiltes Modell durchaus eines — das legt für die BC-5-Kanten SHARED_KERNEL als weitere Lesart nahe.

### 7.3 Kandidaten-Pass (bc-audit-Protokoll)

#### 7.3.1 Einziger belegbarer Sprachbruch: "Konsequenz" (BC-1 vs. BC-3) -- schwach

- **Sachverhalt:** FR-5 trägt eine `usesTerm`-Kante auf TERM-30 `Consequence`. Sichtbar auch in `term_cooccurrence` (TERM-10+TERM-30 in FR-5, TERM-30+TERM-15 in FR-5).
- **Regel diesseits (BC-1):** In FR-5s Text heißt es "Ein Statuswechsel löst keine **Konsequenz** aus: keine Validierungsregel, keine Kopplung an Kanten, keine Reihenfolge." Konsequenz = ausgelöste Wirkung, ein Prosa-Wort ohne Modellstatus.
- **Regel jenseits (BC-3):** TERM-30 definiert Consequence als "positive, negative oder neutrale Folge einer Architekturentscheidung, **als eigene, in der Entscheidung positionierte Ressource** festgehalten" — mit Typ, Position und Identität.
- **Urteil:** Dasselbe Wort, zwei Bedeutungen, unterschiedliche Regeln — formal der einzige Treffer im ganzen Bestand. Aber: Er beruht auf einer einzigen Fundstelle, und die naheliegendste Erklärung ist ein **falsch gesetzter `usesTerm`-Link** in FR-5 (das Prosa-Wort wurde auf den ADR-Begriff verlinkt), nicht eine gewachsene Zweideutigkeit. Wird der Link korrigiert, verschwindet der Beleg. Er taugt daher **nicht** als Rechtfertigung der BC-1/BC-3-Grenze und ist eher ein Datenfehler-Fund. Als Kandidat für eine *neue* Grenze taugt er ohnehin nicht — er läuft entlang einer bereits gezogenen.

#### 7.3.2 Cluster ohne Sprachbruch -- Beobachtungen ohne Kontextvorschlag

**(a) Rollen-Cluster in `role_usecase_matrix`.** ROLE-4 (Coding AI Agent) in allen 4 UCs, ROLE-1 (Requirements Engineer) in 3, **ROLE-2 (Architect) und ROLE-3 (DDD Practitioner) in null**. Die Rollendefinitionen spiegeln den BC-Schnitt exakt: Requirements Engineer → BC-1, Architect → BC-3, DDD Practitioner → BC-2. Das ist eine Aufteilung nach **Zuständigkeit**, die FR-9 selbst ausdrücklich als nicht kontextbegründend ausschließt. Verschärfend: Für ROLE-2/ROLE-3 gibt es null Use Cases, also gar kein Material auf der anderen Seite, gegen das ein Regelunterschied zu belegen wäre. **Kein Kontextvorschlag.**

**(b) Der gesamte Kookkurrenz-Graph ist ein einziger Cluster.** Alle 21 Paare hängen an Requirement (TERM-10), Business Code (TERM-15) und Architecture Model (TERM-12) und stammen aus FR-1..FR-5, FR-7, FR-8, FR-10 und UC1–UC4. Es gibt keinen Begriff mit zwei disjunkten Nachbarschaften, keinen Begriff, der in einer Gruppe von Texten auftaucht und in einer benachbarten systematisch fehlt. Die geforderte Signatur eines Sprachbruchs ist im Kookkurrenz-Material schlicht nicht vorhanden. **Kein Kontextvorschlag.**

**(c) Der Schnitt aller sechs Kontexte faellt nahezu mit dem Ressourcentyp- und Modulschnitt zusammen.** BC-1 ≈ Requirement/Constraint/UseCase, BC-2 ≈ Term + BoundedContext, BC-3 ≈ ADR, BC-4 ≈ Actor/Role, BC-5 ≈ Project, BC-6 ≈ der generische Lesepfad. Jede Grenze verläuft dort, wo im Store ein Ressourcentyp endet — nicht dort, wo ein Sachverhalt andere Regeln bekommt. BC-6 ist der Extremfall: seine domainVision ("wertet lesend **über Kontextgrenzen hinweg** aus") beschreibt eine Zugriffsart, und ein Kontext, der ausdrücklich die Sprachen aller anderen spricht, ist per TERM-19/TERM-25 keine Sprachgrenze. Nach FR-9 wäre keiner dieser sechs Schnitte heute vorschlagsfähig. Das ist **kein Beleg, dass die Grenzen falsch sind** — sie können aus Wissen gezogen sein, das nie in den Store gelangte. Es ist der Befund, dass der Bestand sie nicht trägt. **Kein Kontextvorschlag.**

**(d) Materiallage insgesamt zu duenn fuer das Verfahren.** 11 Requirements, 4 Use Cases (alle vier über Requirements), 0 Actors, 0 Constraints, 4 Rollen davon 2 ungenutzt. Der Skill setzt einen gefüllten Store voraus; dieser ist gefüllt genug, um nicht abgebrochen zu werden, aber zu einseitig, um Kollisionen zu zeigen: es gibt schlicht keine zweite Gegend, in der ein Begriff anders gebraucht würde. Auffällig dabei: **kein einziger Constraint** ist erfasst, obwohl TERM-11 definiert, BC-1 ihn in der domainVision beansprucht und UC1/FR-2 ihn tragend nennen.
## 8. Korpusweite Funde

### 8.1 Entscheidungen

**A1 -- ADR-51 (ACCEPTED) vs. ADR-55 (PROPOSED): echter Widerspruch, kein Ergaenzen.** Beide beginnen wortgleich. ADR-51 zieht den Bericht ausdruecklich in die Ausnahme ("samt dem Bericht, der daraus zusammengesetzt wird"), ADR-55 nimmt ihn ebenso ausdruecklich heraus ("Der Bericht gehoert zur Modellanalyse") und erklaert die Praemisse von ADR-51 fuer widerlegt. Solange beide stehen, ist der Bericht zugleich geduldete Root-Ausnahme und Eigentum der Modellanalyse. Die Kante zwischen beiden ist `relatedTo` -- die Prosa von ADR-55 behauptet aber ein Abloeseverhaeltnis. R7-Fund auf Korpusebene: `relatedTo` traegt hier die Arbeit eines noch nicht moeglichen `supersededBy`.

**A2 -- Dieselbe Konstellation zweimal mehr.** ADR-53 kehrt ADR-13 um (Registry ausserhalb der Kontexte -> Registry ist ein Kontext) und zitiert dessen eigene Negativfolge als Motiv. ADR-52 ersetzt ADR-10 (drei Modell-Kontexte -> sechs Kontexte) und erklaert dessen Leitbegriff "Modell-Kontext" fuer undefiniert. Beide Male: ACCEPTED-Record und widersprechender PROPOSED-Record, verbunden nur durch `relatedTo`. Drei Paare (10/52, 13/53, 51/55) warten auf dieselbe Aufloesung.

**A3 -- Die Buendelung erzeugt die Abloeseketten.** ADR-4 -> ADR-46, ADR-7 -> ADR-51 -> ADR-55: in allen drei Faellen aendert sich nur eine Teilaussage (wo die Kontextliste lebt; wie die Ausnahme zugeschnitten ist), aber weil die unveraenderte Regel im selben Record steht, musste der ganze Record ersetzt und sein Text zu 80 % neu geschrieben werden. ADR-4 wurde am 2026-09-05 angenommen und am selben Tag abgeloest. Das ist kein Einzelfall, sondern die vom Skill benannte Folge der R1-Verletzung ("adr_supersede zeigt auf den ganzen Record").

**A4 -- Die PROPOSED-Gruppe ruht auf sich selbst, mit einem Zirkel.** Jeder der acht neuen Records erklaert als bereits geklaert, was ein anderer nur vorschlaegt: ADR-52 und ADR-48 berufen sich beide auf "seither ist geklaert, was ein Bounded Context im Build ist" -- das ist ADR-48 bzw. ADR-52 selbst (Zirkel). ADR-53 setzt den Shared Kernel (ADR-56) voraus, ADR-55 setzt die Modellanalyse als Kontext (ADR-54) voraus, ADR-49/ADR-58 stuetzen sich gegenseitig. Kein Record der Gruppe ist aus seinem eigenen Text heraus annehmbar; eine Annahmereihenfolge existiert nur, wenn der Zirkel 48/52 vorher aufgeloest wird.

**A5 -- Entstehungsgeschichte im Kontextfeld, systematisch (R3).** ADR-46, ADR-48, ADR-49 erzaehlen ausdruecklich den *ersten Entwurf des eigenen Records* ("Der erste Entwurf dieser Entscheidung wollte ..."), ADR-52/53/54/55 die Beratungslage ("Seither ist grundsaetzlich geklaert ...", "Drei Orte waren bereits verworfen ...", "Drei Umstaende haben sich seither geaendert"). Der Bezug auf einen *realen Vorgaengerrecord* ist legitimer Kontext; der Bezug auf einen nie gespeicherten Entwurf und auf zwischenzeitliche Klaerungen ist es nicht -- ein spaeterer Leser hat weder das eine noch das andere. Sieben von acht neuen Records tragen dieses Muster.

**A6 -- Der Bestand hat die PROPOSED-Entscheidungen schon vollzogen (R8).** `bc_list` fuehrt heute genau sechs Kontexte, drei CORE_DOMAIN und drei SUPPORTING_DOMAIN, darunter BC-4 Akteur, BC-5 Projekt-Registry und BC-6 Modellanalyse mit vollstaendiger Kontextkarte -- also exakt das, was ADR-52, ADR-53 und ADR-54 erst *vorschlagen*. ADR-52 nennt seine eigene Nachpruefbarkeit als Positivfolge ("die Zaehlung ist im Modell selbst ablesbar"), und sie stimmt bereits. Der Zustand ist damit gegenueber den Records vorausgelaufen: nicht "ACCEPTED, aber nicht befolgt", sondern "befolgt, aber nicht ACCEPTED". Zugleich steht ADR-13, den ADR-53 umkehrt, unveraendert auf ACCEPTED.

**A7 -- Doppelt entschiedene Einzelsachen.** (a) Das reservierte System-Dataset der Registry ist die Ausnahmeklausel von ADR-25 *und* die Entscheidung von ADR-27, beide am selben Tag, gegenseitig verlinkt -- konsistent, aber an zwei Orten pflegbar. (b) "Der Bericht gehoert zur Modellanalyse" steht wortgleich in ADR-54 und ADR-55. (c) Die Zugehoerigkeit von Akteur, Registry und Analyse zu den Kontexten steht in ADR-36/53/54 *und* nochmals als Liste in ADR-52.

**A8 -- `affects`-Kanten sind ohne erkennbares Kriterium gesetzt (R6).** Praezise: ADR-37 (BC-4), ADR-20/25/27/53 (BC-5), ADR-54 (BC-6), ADR-57 (BC-1/BC-2 = genau die Mehr-Komponenten-Kontexte). Inkonsistent: ADR-13 entscheidet ueber die Projekt-Registry und traegt keine Kante, waehrend vier Nachbarrecords zum selben Gegenstand BC-5 tragen; ADR-10 legt den Inhalt von BC-1/2/3 fest und traegt keine; ADR-48 nennt sechs Kontexte im Text und bindet zwei; ADR-52 entscheidet ueber sechs und bindet die drei stuetzenden. Die Regel "gezielt, nicht erschoepfend" erklaert keinen dieser vier Faelle.

**A9 -- Zwei Beziehungstypen der Entscheidungen fehlen in der Kontextkarte.** ADR-56 beschliesst einen Shared Kernel aller Kontexte, ADR-19 einen Anti-Corruption Layer; `bc_list` kennt ausschliesslich PUBLISHED_LANGUAGE-Kanten. Fuer ADR-56 (PROPOSED) erwartbar, fuer ADR-19 (ACCEPTED) nur deshalb unschaedlich, weil die betroffenen Kontexte noch nicht existieren. Kein Defekt, aber der Punkt, an dem die Karte den Records nachlaufen muss.

**A10 -- Vier Records sind dem R0-Muster "Bausteinsicht" bzw. "Status quo" naeher als einer Entscheidung.** ADR-52 (Liste der sechs Kontexte samt Zuordnung), ADR-46 (Liste der acht Lifecycle-Kontexte), ADR-10 (Liste der drei Kontexte) -- in allen dreien ist die eigentliche Entscheidung das Schnittkriterium, nicht die Aufzaehlung, und die Aufzaehlung veraltet mit dem naechsten Kontext. ADR-58 entscheidet, ein Schema-Modul *nicht* zu bauen, und benennt in seiner eigenen Neutralfolge die Umkehr als billig ("ein Modulschnitt, kein Umbau des Kerns") -- damit faellt Q2 nach dem Zeugnis des Records selbst. Diese vier gehoeren dem Nutzer vorgelegt, nicht vom Reviewer entschieden.

### 8.2 Requirements

**Redundanz**
- "Eine Änderung/Verknüpfung ist unabhängig vom Status möglich" steht als AC in **FR-3 und FR-4**, und FR-5 AC6 behauptet dasselbe aus der Gegenrichtung. Drei Stellen tragen eine Aussage, die genau eine ist: Status ist folgenlos (so schon in FR-5 `rationale`).
- "denselben Detailgrad wie das gezielte Nachschlagen" steht in **FR-2 AC1 und FR-8 AC4** — derselbe undefinierte Maßstab, zweimal gepflegt.
- Zweck-/Begründungssätze stehen in **FR-4** und **NFR-1** in der `description`, obwohl (NFR-1) das `rationale`-Feld dasselbe bereits sagt bzw. (FR-4) leer ist.

**Widerspruch**
- **FR-3 ⟂ ausgelieferter Werkzeugvertrag**: FR-3 sagt, eine Änderung tastet die Term-/Constraint-Verknüpfungen nicht an, und AC4 sagt, mitgegebene Akzeptanzkriterien ersetzen die bestehenden vollständig. `req_update` kann laut Skill-Doku beides nicht so: es ersetzt Term-Kanten wholesale über `usesTermCodes` und kennt für Akzeptanzkriterien drei enge Parameter (anhängen / positionsweise korrigieren / entfernen), aber kein Voll-Ersetzen. Eines von beiden ist veraltet.
- **FR-2 intern**: die `description` bindet das Nachschlagen an den Business-Code, `rationale` und AC4 setzen dagegen mehrere Kennungsformen samt Mehrdeutigkeitsprüfung voraus. Ein Leser bekommt zwei verschiedene Verträge.
- **FR-3 ⟂ FR-4**: FR-4 kennt nur das Herstellen einer Verknüpfung, FR-3 verbietet dem Änderungsweg, Verknüpfungen anzutasten. Zusammen genommen gibt der Korpus **keinen Weg, eine Term-Verknüpfung wieder zu lösen**.
- **FR-7 ⟂ FR-1/FR-3**: FR-7 fordert die Herkunft als Feld eines Requirements; FR-1 und FR-3 zählen die Felder beim Anlegen und Ändern auf und lassen die Herkunft beide aus.
- **FR-10 ⟂ dem Requirement-Korpus selbst**: FR-10 verlangt ein Wort je Begriff über alle Sprachen. Die Requirement-Texte benutzen durchweg deutsche Paraphrasen für englisch gelabelte Terms — "Akteur"/Actor (FR-6), "Rolle"/"Träger"/Role+Actor (FR-7), "Begriffe"/Term (FR-4, FR-9), "Anforderung"/Requirement (UC2-Trigger). Genau die Erwähnungserkennung, mit der FR-10 begründet wird, greift auf diesen Text nicht.
- **FR-5 verlinkt TERM-30 "Consequence"** (die positionierte Folge einer Architekturentscheidung), während sein Text "Konsequenz" im Alltagssinn benutzt ("löst keine Konsequenz aus"). Homonym-Fehlverknüpfung, kein Sprachgebrauch des Glossars.
- **FR-6 verlinkt TERM-16 "Group Actor" nicht** — den Begriff, den es einführt; verlinkt ist nur TERM-2 Actor.

**Fehlende Begriffe (tragende Woerter ohne Glossareintrag)**
"Status" und "Priorität" (FR-1/3/5 — zentral, kein Term), "Sprachbruch" (FR-9 — das einzige Kriterium des Requirements), "Modellbestand"/"Lesefläche" (FR-8), "Daemon"/"Allowlist"/"Loopback"/"Origin" (NFR-1 — durchweg Lösungsvokabular).

**Luecken im Set gegenueber den vier erfassten Rollen**
- **ROLE-1 Requirements Engineer**: abgedeckt sind Requirements anlegen/lesen/ändern/verknüpfen/Status. Nicht abgedeckt: **Constraint anlegen oder ändern** (FR-2 liest sie, nichts erzeugt sie), **Use Case anlegen/ändern** (TERM-27 definiert, UC1–UC4 existieren im Store — kein Requirement deckt das), **Term anlegen/ändern** (nur FR-10 regelt eine Label-Regel), **Löschen** irgendeiner Ressource.
- **ROLE-2 Architect**: **kein einziges Requirement**. TERM-29/30/31 (ADR, Consequence, Considered Option) sind definiert, der ganze Entscheidungs-Lebenszyklus ist im Korpus unbelegt.
- **ROLE-3 DDD Practitioner**: nur FR-9 (Audit-Verfahren). Nichts zum Erfassen eines Bounded Context, nichts zu Context Relationship (TERM-32), nichts zu Subdomain (TERM-33).
- **ROLE-4 Coding AI Agent**: als treibende Rolle in FR-1..FR-5 genannt — aber FR-7 AC3 stuft genau eine solche Systemrolle als "üblicherweise ohne eigenes Interesse" ein. Fünf Requirements verankern ihren Bedarf damit an der Rolle, die das Modell selbst nicht als Bedarfsträger ansieht; der eigentliche Bedarfsträger ROLE-1 erscheint nur als Halbsatz "stellvertretend für".
- **Ohne Rolle im Set**: Projekt-Registry und Anker (TERM-17/18/23 definiert, `project_*` existiert) — weder Rolle noch Requirement. Revision/Historie (TERM-20 definiert) — kein Requirement, obwohl FR-3 AC7 Nebenläufigkeitsschutz voraussetzt.
- **Nicht-funktional**: NFR-1 ist der einzige NFR und deckt Origin/Host-Prüfung ab. Nichts zu Antwortzeiten, Datenhaltbarkeit, gleichzeitigem Zugriff mehrerer Sessions (im Projekt real: ein geteilter Daemon), Projekt-Isolation, Sicherung/Wiederherstellung.

**Leerer Constraint-Bestand**
Der Store fuehrt **null Constraints**. Gleichzeitig ist "Constraint" ein definierter Begriff (TERM-11) und wird von **fünf** Requirements in Anspruch genommen: FR-2 verspricht Nachschlagen und Auflisten von Constraints, FR-3/FR-4/FR-5 sichern zu, dass Constraint-Verknüpfungen unangetastet bleiben. Der Korpus beschreibt damit durchgängig Verhalten an einem Ressourcentyp, für den kein einziges Exemplar und kein einziges erzeugendes Requirement existiert — die Zusagen in FR-2 bis FR-5 sind an keiner Instanz je abgenommen worden.

### 8.3 Use Cases

1. **Abdeckung des Sets:** nur der Requirement-Lifecycle plus lesender Constraint-Zugriff. Kein UC für Term, Bounded Context, ADR, Rolle, Actor, Projekt — und kein UC für Constraint **erfassen/ändern**, obwohl UC1 sie liest. Besonders hart: UC2 Schritt 6 und UC3 Schritt 6 verknüpfen Terms, die Fähigkeit "Term erfassen" wird also von zwei Abläufen vorausgesetzt und von keinem beschrieben.
2. **Rollenverteilung schief:** ROLE-2 (Architect) und ROLE-3 (DDD Practitioner) treiben **keinen** UC — Registereinträge ohne Ablauf. ROLE-4 ist in allen vier, in UC1 sogar allein primär. Der Vermittler ist überall, die Fachrollen fast nirgends: die Werkzeugsicht ist zur Hauptsicht geworden.
3. **ROLE-4 verdeckt den Bedarfsträger** genau dort, wo es zählt (UC1). In UC2-4 ist die Zuordnung dagegen richtig (ROLE-1 primär).
4. **Überschneidung:** UC2/UC3/UC4 tragen dasselbe Skelett (RE benennt → Agent liest/befragt → RE bestätigt → Agent schreibt) und dieselbe Rollenpaarung. Die Extension "Business-Code existiert nicht → Fehler" steht dreimal nahezu wortgleich (UC1, UC3, UC4), obwohl sie eine einmal zugesagte Systemeigenschaft ist (FR-2-AK).
5. **`scope` ist in allen vier UCs leer.** Die Systemgrenze ist nirgends erklärt — und an ihr hängt der einzige Schritt im Set, der klar ausserhalb liegt (UC1 Schritt 2).
6. **Titelmuster:** UC1/UC2/UC3 tragen den Namen der Systemoperation (nachschlagen/erfassen/korrigieren) statt eines Rollenziels; nur UC4 bricht damit. Das ist das Signal der Checkliste, dass das goal-in-context nie wirklich neu formuliert wurde.
7. **Fehlerbilder aus den realisierten FR nicht durchgezogen:** FR-2 (Mehrdeutigkeit), FR-1 (Ablehnung ohne Akzeptanzkriterium), FR-4 (unbekannter Term), FR-3 (AK-Vollersatz) haben jeweils ein Akzeptanzkriterium, aber keine Entsprechung im Ablauf. Umgekehrt gilt: FR-3 ist im Ablauf am besten abgedeckt.
8. **Kein UC nennt ein NFR in `realises`;** keiner der vier Abläufe bedenkt Nichterreichbarkeit des Stores, Timeout oder Teilausfall — bei einem Betriebsmodell mit separatem Daemon-Prozess auffällig.
9. **Sprache:** alle vier UCs kommen unter `displayLocale=de` ohne `[fallback: …]`-Marker zurück; die deutsche Fassung existiert real.

### 8.4 Glossar

**G1 -- Zirkelkette Metamodell ↔ Architekturmodell ↔ Ressource (P0).** TERM-1 definiert sich über "Modell", TERM-12 über "Metamodell", TERM-12 über "Ressourcen" und TERM-14 über "Architekturmodell". Zwei geschlossene 2er-Zirkel im Zentrum des Glossars — ein Leser, der keinen der drei Begriffe kennt, kommt an keiner Stelle heraus. Ein Anker außerhalb (was ist ein *Modell*?) fehlt.

**G2 -- Der Oberbegriff "Domaene" fehlt, obwohl er im Metamodell traegt (P0).** `arkddd:Domain` ist eine modellierte Klasse (`arknet-ddd.ttl:47`), `arkddd:Subdomain rdfs:subClassOf arkddd:Domain` (Z. 74-76), und `arknet-shapes.ttl:64` verlangt für jeden BoundedContext ein `partOf` auf Domain **oder** Subdomain. Trotzdem gibt es keinen Term "Domain". TERM-33 definiert Subdomain gegen "Gesamtdomäne", TERM-19 gegen "Domänenmodell" — beides undefinierte Wörter. Genau der Fall, den die Checkliste mit "grep die Vokabular-Quelle, nicht die Prosa" abfängt.

**G3 -- Die broader-Hierarchie ist halb gebaut (P0).** `broader:TERM-14 (Resource)` tragen nur TERM-29, TERM-30, TERM-32 — die ADR-Familie. Requirement (10), Constraint (11), Term (13), Bounded Context (19), Revision (20), Use Case (27), Acceptance Criterion (28) und Considered Option (31) sind ebenso einzeln adressierbare Elemente eines Architekturmodells, tragen die Kante aber nicht. Entweder gilt die Kante für alle oder für keinen; im jetzigen Zustand behauptet die Struktur, ADR-Bestandteile seien Ressourcen und Requirements nicht.

**G4 -- Die ADR-Familie ist nicht verkantet (P1).** TERM-29 nennt Folgen und erwogene Optionen im Fließtext, hat aber weder zu TERM-30 noch zu TERM-31 eine `related`-Kante; umgekehrt genauso. TERM-31 hat gar keine Kante — das ist der `orphan_check`-Fund, und seine Ursache ist hier: die zwei Kanten, die TERM-30 hat (`broader:14`) und TERM-31 fehlen, sind schlicht nie gesetzt worden. Zusätzlich definieren sich TERM-30 und TERM-31 über "Architekturentscheidung", während der Term "Architecture Decision Record" heißt — die Entscheidung selbst ist kein Begriff im Glossar, nur ihre Aufzeichnung.

**G5 -- Use Case haengt fast frei (P1).** TERM-27 trägt nur `related:TERM-21`. Im Metamodell hängt ein Use Case an Requirements (`realises`), Constraints (`uc_link_constraint`) und Terms (`uc_link_term`); keine dieser drei Nachbarschaften ist im Glossar abgebildet. Spiegelbildlich fehlt TERM-10 die Kante zu TERM-27.

**G6 -- Actor-Geschwister sind asymmetrisch verkantet (P2).** TERM-3↔5, TERM-4↔5 sind `related`, TERM-3↔4 nicht, TERM-16 zu keinem. Zugleich ist jede dieser Geschwisterkanten redundant: die gemeinsame `broader:TERM-2` sagt dasselbe. Entweder alle vier untereinander oder keine — und die Begründung, warum eine Geschwisterkante neben der Hierarchie überhaupt Information trägt, fehlt.

**G7 -- Doppelte Abgrenzungslisten (P2).** TERM-5 und TERM-16 zählen beide die Abgrenzung gegen die Geschwister erneut auf, TERM-29 zählt seine Bestandteile auf, TERM-19 und TERM-25 erzählen beide die Grenzrelativität, TERM-13 und TERM-25 beide die Eindeutigkeit. Fünf Stellen, die von Hand synchron gehalten werden müssen — die Checkliste verlangt hier Querverweis statt Wiederholung.

**G8 -- Drei Definitionen nennen das Produkt (P1).** TERM-1, TERM-12 und TERM-17 definieren über "mit arknet erstellt/erfasst/beschrieben". Ein Metamodell, ein Architekturmodell und ein Projekt sind keine arknet-Begriffe; die Werkzeugbindung macht die Definitionen zirkulär gegenüber dem Werkzeug und verletzt die Implementierungsfreiheit. Anders als bei TERM-23/26 ist das leicht zu heilen.

**G9 -- TERM-23 Dataset und TERM-26 Component gehoeren so nicht ins Glossar (P1).** Dataset definiert sich über Speichereinheit, Abfragegrenze und eine reservierte Projekt-Registry — drei Architekturentscheidungen (Persistenz, Query-Scope, System-Dataset) in einer Bedeutungsdefinition; das ist ADR-Stoff. Component hat überhaupt keine Gattung, keine Ontologie-Klasse und definiert sich über "Schnittstelle" und "gruppiert Funktionalität" — ein Design-Begriff, kein Domänenbegriff. Von allen 27 ist TERM-26 die einzige Definition, die die Grundregel (Gattung + unterscheidendes Merkmal) formal verfehlt.

**G10 -- Sprachbruch: "Anforderung" vs. "Requirement" (P2).** TERM-21 (de) schreibt "als Interessenträger hinter einer Anforderung", während TERM-10/11/28 (de) durchgängig "Requirement" schreiben. Zwei Wörter für einen Begriff in derselben Sprache, und "Anforderung" wird von der Mention-Erkennung nicht aufgelöst.

**G11 -- Label-Regel formal eingehalten, praktisch wirkungslos (P2).** Alle 27 Labels tragen unter `de` und `en` dasselbe Wort — keine übersetzte prefLabel, Regel erfüllt. Die deutschen Definitionen übersetzen das Label-Wort aber im Fließtext: "Metamodell" (TERM-1), "Architekturmodell" (12, 13, 14, 15), "Ressource" (14, 15, 20), "Anker" (18), "Projekt" (17, 18, 23, 33), "Komponente" (26), "Begriff" (13, 19). Kein einziger deutscher Definitionssatz nennt das Label so, wie es im Glossar steht — deutsche Prosa erzeugt damit keine auflösbaren Term-Erwähnungen. Das ist keine Verletzung der Label-Regel, aber es hebt ihren Zweck auf und gehört als Projektentscheidung geklärt.

**G12 -- Terminologie-Abweichung zur Ontologie bei TERM-33 (P2).** Der Term sagt "Core, Supporting oder Generic *Subdomain*"; `arknet-ddd.ttl:59-71` labelt dieselben Individuen "Core Domain"/"Supporting Domain"/"Generic Domain" als `SubdomainType`. Zwei Schreibweisen für dieselbe Klassifikation.

**G13 -- "Constraint" ist zu eng definiert (P2).** "schränkt den Lösungsraum für ein Requirement ein" — im Modell hängt ein Constraint auch an Use Cases (`uc_link_constraint`). Die Definition schließt die Hälfte der modellierten Verwendung aus.

**G14 -- Externe Bedeutungskollisionen ohne Aufloesung (P2).** TERM-2 Actor (UML/Jacobson: Actor *ist* die Rolle — hier bewusst umgedreht, in der Definition wenigstens adressiert), TERM-14 Resource (RDF/REST: alles mit IRI), TERM-23 Dataset (RDF Dataset = Named Graphs), TERM-11 Constraint (breit als jede Invariante gelesen), TERM-26 Component (C4/UML/Maven). Nur TERM-17 Project grenzt seine externe Bedeutung explizit ab — das ist das Vorbild, das den anderen fünf fehlt.

**G15 -- Beobachtung zur Verwendung, kein Regelfund.** `term_cooccurrence` zeigt nur 10 Terms überhaupt in Requirement-/Use-Case-Text (10, 11, 12, 13, 14, 15, 17, 21, 28, 30). Die ganze DDD-Familie (19, 25, 26, 32, 33), die Actor-Familie (2-5, 16), 18, 20, 23, 27, 29, 31 kommt in keinem Paar vor. Caveat: das Tool sieht nur literale Ko-Vorkommen *paarweise*, ein allein genannter Term erscheint nicht — es beweist keine Nichtverwendung, ist aber ein Signal, dass ein Teil des Glossars ohne Bezug zum Requirement-Bestand geführt wird.

**Keine Funde bei:** Config-Freiheit (kein Term friert einen datierten/konfigurierbaren Wert ein), Actor/Role-Fehleinordnung (TERM-2 und TERM-21 beschreiben die Metamodell-Klassen, nicht konkrete Actors/Rollen — korrekt als Terms), Definition ohne Sprachvariante (jede der 27 Definitionen trägt `de` **und** `en`; die einzigen `[fallback: …]`-Marker betreffen prefLabel und sind der bekannte `store_check`-Befund).
## 9. Abdeckung dieses Durchlaufs

| Ressourcentyp | Bestand | Leser-Regelwerk in diesem Lauf | Was dieser Review NICHT erreicht |
|---|---|---|---|
| Architecture Decision Record | 30 | Regeltabelle R0-R8 aus `/arknet:adr`, eine Zeile je Record | -- der Modus ist bereits eine Tabelle und wurde unveraendert uebertragen |
| Requirement | 11 | Full-Set-Checkliste aus `/arknet:req-interview`, als Raster gerendert | Die Checkliste ist fuer einen Dialog geschrieben: jede Frage, die sie einem Menschen stellen wuerde, steht hier als Fund in einer Zelle statt als beantwortete Frage |
| Constraint | 0 | entfaellt | Ein leerer Bestand ist kein Befund -- der *Grund*, dass fuenf Requirements Verhalten an Constraints zusagen, steht in 8.2 |
| Use Case | 4 | Full-Set-Checkliste aus `/arknet:req-interview` | wie Requirement |
| Glossarbegriff | 27 | Full-Set-Checkliste aus `/arknet:req-interview` | wie Requirement |
| Bounded Context | 6 | `/arknet:bc-audit` findet **Kandidaten** im Bestand | Es gibt **keinen** Modus, der eine bereits eingetragene Grenze dem Sprachbruch-Test unterwirft. Ein Kontext, der nie einer war, wird von diesem Lauf nicht gefunden -- alle Urteile in 7.1 sind Leserlektuere |
| Context Relationship | 13 | keiner | Ein eingetragener Beziehungstyp wird nirgends nachgeprueft. `/arknet:context-map` elicitiert nur. 7.2 ist durchweg Leserbefund |
| Actor | 0 | keiner | Nicht reviewt. Der Bestand ist leer -- aber das ist eine Zaehlung, keine Pruefung |
| Role | 4 | keiner | **Nicht reviewt.** Vier Rollen liegen im Store und wurden von keiner Regel geprueft; was in 8.3 ueber Rollen steht, faellt als Nebenprodukt der Use-Case-Pruefung an |

### 9.1 Was die einzelnen Leser nicht erreicht haben

**Entscheidungen.** `impact_analysis` wurde nicht ausgefuehrt; R6/R7 ruhen allein
auf `adr_get`/`adr_list`/`bc_list`. Die TERM-Codes der Records wurden nicht
aufgeloest -- die Term-Haelfte von R6 ist nur strukturell geprueft, nicht
inhaltlich. Kein Abgleich gegen den Modulbaum: R8 stuetzt sich auf den Store.
Nur `de` gelesen.

**Requirements.** Nur `displayLocale=de`. Ob die englischen Fassungen dieselben
Behauptungen tragen (Projektregel: beide Sprachen gleichrangig), ist ungeprueft.
`qualityCategory` von NFR-1 ist im Lesepfad nicht sichtbar und darum nicht
feststellbar.

**Use Cases.** Nur `de`. Die verwendeten Terms wurden nicht gelesen -- die
`usesTerm`-Kanten sind auf Vorhandensein, nicht auf Passung geprueft. Ob ein UC
an einen Constraint gebunden ist, gibt `uc_get` nicht aus.

**Glossar.** Der Abgleich Glossar gegen ADR-Korpus und Requirement-Texte wurde
nicht gefuehrt (G10/G12 sind die Faelle, die *innerhalb* des Glossars bzw. gegen
die Ontologie-Datei sichtbar wurden). Der Ontologie-Abgleich lief gegen arkddd,
arkarch, arkproc, arkprj, arkprov, nicht gegen `parked/*.ttl`. G15 ist keine
Nullmessung: `term_cooccurrence` liefert nur Paare, keine Einzelnennungen.

**Bounded Contexts.** Siehe die Einschraenkung in Abschnitt 7. Zusaetzlich: die
Subdomain-Einstufungen (CORE/SUPPORTING/GENERIC) haben in keinem Skill eine
Regel -- die Spalte in 7.1 ist reine Leserplausibilitaet gegen TERM-33. Der
ADR-Korpus wurde vom BC-Leser nicht gelesen; ob eine Entscheidung den Schnitt
begruendet, ist dort offen (und haette den Kandidaten-Pass ohnehin nicht speisen
duerfen).

## 10. Was daraus folgt

Nach Wirkung geordnet. Nichts davon ist getan; jede Zeile nennt das Skill, das
sie aufloesen wuerde.

| # | Fund | Wirkung | Aufzuloesen mit |
|---|---|---|---|
| 1 | Drei ACCEPTED/PROPOSED-Paare widersprechen einander offen (ADR-51/55, ADR-13/53, ADR-10/52), verbunden nur durch `relatedTo` (A1, A2) | Der Korpus behauptet zwei unvereinbare Gestalten zugleich. Ein Leser, der nur `adr_list` sieht, haelt beide fuer gueltig | `/arknet:adr` -- Annahmereihenfolge festlegen, dann `adr_supersede` statt `relatedTo` |
| 2 | Die PROPOSED-Gruppe ruht auf sich selbst; ADR-48 und ADR-52 berufen sich wechselseitig aufeinander als bereits geklaert (A4) | Kein Record der Gruppe ist aus eigenem Text annehmbar; ohne Aufloesung des Zirkels gibt es keine gueltige Reihenfolge | `/arknet:adr` |
| 3 | Der Bestand hat die PROPOSED-Entscheidungen bereits vollzogen: `bc_list` zeigt genau die sechs Kontexte, die ADR-52/53/54 erst vorschlagen (A6) | "Befolgt, aber nicht ACCEPTED" -- der Status luegt in der Gegenrichtung, und ADR-13 steht dabei unveraendert auf ACCEPTED | `/arknet:adr` |
| 4 | FR-3 widerspricht dem ausgelieferten Werkzeugvertrag (`req_update` ersetzt Term-Kanten wholesale, kennt kein AK-Voll-Ersetzen) (8.2) | Ein Requirement sichert Verhalten zu, das das Werkzeug nicht hat. Eines von beiden ist veraltet -- der Fund trifft Modell **und** Code | `/arknet:req-interview`; bei Werkzeugseite ein Issue |
| 5 | FR-3 ⟂ FR-4: der Korpus kennt keinen Weg, eine Term-Verknuepfung wieder zu loesen (8.2); UC3 zeigt dieselbe Luecke im Ablauf (8.3) | Echte Funktionsluecke, nicht nur Textmangel | `/arknet:req-interview` |
| 6 | Glossar-Zirkel Metamodell/Architekturmodell/Ressource; "Domain" fehlt als Term, obwohl `arknet-shapes.ttl` ein `partOf` darauf verlangt (G1, G2) | Das Zentrum des Glossars ist von aussen nicht betretbar, und ein SHACL-pflichtiger Oberbegriff hat keine Definition | `/arknet:req-interview` |
| 7 | `broader:TERM-14` tragen nur die drei ADR-Begriffe; acht gleichrangige Ressourcentypen nicht (G3) | Die Struktur behauptet, ADR-Bestandteile seien Ressourcen und Requirements nicht | `/arknet:req-interview` |
| 8 | BC-3 und BC-6 tragen null Begriffskanten; 14 von 27 Terms haengen an keinem Kontext (7.1) | Zwei Kontextgrenzen sind im Store unbelegbar; fuer das querschnittliche Vokabular fehlt eine Ausdrucksform | `/arknet:bc-audit`, `bc_link_term`; die Ausdrucksform ist ein Metamodell-Thema |
| 9 | Alle 13 Context Relationships tragen denselben Typ; die Begruendung gilt fuer alle gleich (7.2) | Eine Karte, die ueberall dasselbe sagt, unterscheidet nichts. Wiederkehrender Alternativkandidat: CONFORMIST | `/arknet:context-map` |
| 10 | Die R1-Buendelung ist die Ursache der Abloeseketten: 21 von 30 Records tragen mehr als eine Entscheidung (3.1, A3) | Jede Teilkorrektur erzwingt eine Vollabloesung -- ADR-4 wurde am Tag seiner Annahme abgeloest | `/arknet:adr`, beim naechsten Schnitt |
| 11 | ROLE-2 und ROLE-3 treiben keinen Use Case und tragen kein Requirement; ROLE-4 ist in UC1 allein primaer (8.2, 8.3) | Der Vermittler ist ueberall, die Fachrollen nirgends -- die Werkzeugsicht hat die Fachsicht verdraengt | `/arknet:req-interview` |
| 12 | Null Constraints, waehrend fuenf Requirements Verhalten an ihnen zusagen (8.2, 7.3.2d) | Zusagen ohne eine einzige Instanz, an der sie je abgenommen wurden | `/arknet:req-interview` |
| 13 | FR-5 verlinkt TERM-30 `Consequence` fuer ein Alltagswort (8.2, 7.3.1) | Ein einzelner falscher `usesTerm`-Link -- billig zu heilen, erzeugt aber den einzigen formalen "Sprachbruch" des ganzen Bestands | `/arknet:req-interview` |
| 14 | Sieben `prefLabel` ohne `en` (2.3); die deutschen Definitionen paraphrasieren durchweg das englische Label (G11) | Die Erwaehnungserkennung, mit der FR-10 begruendet wird, greift auf deutsche Prosa nicht | `/arknet:req-interview` |
| 15 | Sechs Requirements ohne realisierenden Use Case; kein UC nennt ein NFR (2.2, 8.3) | Die Haelfte des Requirement-Bestands hat keinen Ablauf, der sie einloest | `/arknet:req-interview` |
| 16 | Vier Records sind eher Bausteinsicht oder Status quo als Entscheidung (A10) | Aufzaehlungen veralten mit dem naechsten Kontext; ADR-58 bezeugt die eigene Billigkeit selbst | `/arknet:adr` -- Vorlage an den Nutzer, keine Reviewer-Entscheidung |
| 17 | TERM-23 Dataset und TERM-26 Component tragen Architektur- statt Bedeutungsdefinitionen; TERM-26 hat gar keine Gattung (G9) | Zwei Glossareintraege, die eigentlich ADR-Stoff sind | `/arknet:req-interview`, ggf. `/arknet:adr` |
| 18 | Die `affects`-Kanten der Entscheidungen folgen keinem erkennbaren Kriterium (A8) | Die 20 kantenlosen Records aus `adr_check` sind teils richtig kantenlos, teils schlicht vergessen -- das ist von aussen nicht unterscheidbar | `/arknet:adr` |

**Nicht in dieser Liste, weil kein Regelwerk sie geprueft hat:** die vier Rollen
und der (leere) Actor-Bestand. Fuer beide gibt es heute keinen Leser-Pruefmodus;
sie sind ungeprueft, nicht in Ordnung.

## 11. Vorbeugung -- welche Ursache traegt welchen Fund

Die Liste in Abschnitt 10 sagt, was zu tun ist. Dieser Abschnitt sagt, warum es
entstanden ist und was es kuenftig verhindern wuerde. Getrennt nach dem, was
schon geregelt ist und nur eingehalten werden muss, dem, was eine Werkzeug- oder
Metamodell-Luecke ist, und dem, was eine Frage der Kadenz ist.

### 11.1 Regel existiert, wurde nicht gefuehrt

| Ursache | Was sie erzeugt hat | Vorbeugung |
|---|---|---|
| Der Unabhaengigkeitstest ("eine Entscheidung je Record") wurde nicht **schriftlich** gefuehrt | 21 von 30 Records tragen mehr als eine Entscheidung (3.1). Daraus folgen die Abloeseketten: ADR-4 -> ADR-46, ADR-7 -> ADR-51 -> ADR-55. Jede Teilkorrektur erzwingt eine Vollabloesung, weil die unveraenderte Regel im selben Record steht (A3) | Der Test ist bereits Regel in `/arknet:adr`. Was fehlte, ist der Zwang, ihn als Text abzulegen -- ein Test, den man im Kopf fuehrt, findet nichts. Dieser Review hat ihn schriftlich gefuehrt und dabei 21 Faelle gefunden, wo `adr_check` null meldet |
| Ein PROPOSED-Record wurde als Arbeitsflaeche benutzt statt als Entscheidung | ADR-48 hat ueber 15 In-Place-Revisionen einen Kontext bekommen, der die eigene Entwurfsgeschichte erzaehlt. Sieben von acht neuen Records tragen dieses Muster (A5) | Die Regel steht seit dem Durchgang vom 2026-09-08 fest (Entscheidung bleibt im Issue-Thread, bis der Entscheidungssatz steht). Der Bestand traegt die Folgen noch -- die betroffenen Kontextfelder sind beim Annehmen zu bereinigen, sonst wird die Entwurfsgeschichte mit ACCEPTED eingefroren |
| Kanten wurden gesetzt, wo sie auffielen, nicht nach Kriterium | `affects` ist bei ADR-37/20/25/27/53/54/57 praezise und bei ADR-13/10/48/52 gar nicht oder halb (A8). Die 20 kantenlosen Records aus `adr_check` sind dadurch nicht mehr interpretierbar: richtig kantenlos und schlicht vergessen sehen gleich aus | Ein Satz im Skill, wann eine `affects`-Kante Pflicht ist (Faustregel aus dem Bestand: wer ueber die *Gestalt* eines Kontexts entscheidet, bindet ihn; wer ueber eine projektweite Technik entscheidet, bindet nichts) |

### 11.2 Werkzeug- oder Metamodell-Luecke -- verdient je ein Issue

| Luecke | Was sie erzwingt | Vorbeugung |
|---|---|---|
| `adr_supersede` verlangt **beide** Seiten ACCEPTED | Drei Abloeseverhaeltnisse (ADR-51/55, ADR-13/53, ADR-10/52) stehen als `relatedTo` im Graph, waehrend die Prosa Abloesung behauptet (A1, A2). Der Graph sagt "verwandt", der Text sagt "ersetzt" -- ein Leser, der nur `adr_list` sieht, haelt beide fuer gueltig | Entweder `adr_supersede` mit PROPOSED-Nachfolger zulassen (die Abloesung wird mit der Annahme wirksam), oder eine eigene Kante fuer den vorgeschlagenen Nachfolger. Solange keins von beidem existiert, erzeugt das Werkzeug den unehrlichen Zustand selbst |
| Kein Pruefmodus fuer Bounded Context, Context Relationship, Role, Actor | Vier Ressourcentypen sind in diesem Lauf **ungeprueft** (Abschnitt 9). BC-3 und BC-6 haetten sonst auffallen muessen: null Begriffskanten, Grenze im Store unbelegbar. Die 13 uniformen PUBLISHED_LANGUAGE-Kanten ebenso | Review-Modi in `/arknet:bc-audit` (bestehende Grenze gegen den Sprachbruch-Test) und `/arknet:context-map` (eingetragener Typ gegen das Material) nachziehen; fuer Role/Actor ueberhaupt erst einen. Ohne das bleibt jeder Store-Review an derselben Stelle blind, und die Luecke sieht im Bericht aus wie ein sauberes Ergebnis |
| Kein Ausdrucksmittel fuer querschnittliches Vokabular | 14 von 27 Begriffen haengen an keinem Kontext (7.1). Das sind nicht vergessene Kanten, sondern der gemeinsame Bestand -- `bc_link_term` an sechs Kontexte zu haengen wuerde die Aussage "gemeinsam" gerade vernichten | Ein Shared-Kernel- bzw. Published-Language-Bestand im Metamodell. ADR-56 entscheidet ihn fuer den Code, das Modell kann ihn nicht ausdruecken |
| `broader` wird beim Schreiben nicht eingefordert | Nur die drei ADR-Begriffe tragen `broader:TERM-14`; acht gleichrangige Ressourcentypen nicht (G3). Die Struktur behauptet dadurch, ADR-Bestandteile seien Ressourcen und Requirements nicht | Eine `store_check`-Regel waere billig: Term, dessen Definition ihn als Element eines Architekturmodells fuehrt, ohne `broader` auf TERM-14 |
| Mention-Erkennung trifft nur das Label, nicht seine Uebersetzung | Kein einziger deutscher Definitionssatz nennt sein Label so, wie es im Glossar steht (G11). FR-10 begruendet die Ein-Wort-Regel mit genau dieser Erkennung -- auf deutscher Prosa greift sie nicht | Entweder `skos:altLabel` je Sprache zulassen (dann findet die Erkennung "Ressource" fuer "Resource"), oder die Regel als das benennen, was sie dann ist: eine Zusage, die nur fuer englische Prosa gilt |
| Requirement und Werkzeugvertrag driften unbemerkt | FR-3 sichert zu, dass eine Aenderung Term-Kanten nicht antastet; `req_update` ersetzt sie ueber `usesTermCodes` wholesale (8.2). Nichts haelt die beiden synchron | Der Abgleich Tool-Schema gegen die Requirements, die es einloesen soll, hat heute keinen Ort. Kandidat fuer eine `store_check`-Regel gibt es nicht -- das ist eher ein wiederkehrender Punkt im Store-Review |

### 11.3 Kadenz statt Regel

- **Der Bestand ist den Records vorausgelaufen** (A6): `bc_list` fuehrt die sechs Kontexte, die ADR-52/53/54 erst vorschlagen. Nicht "beschlossen und nicht befolgt", sondern "befolgt und nicht beschlossen". Das entsteht, wenn Store-Schreibaufrufe und Record-Annahme verschiedene Taktungen haben. Vorbeugung ist keine Regel, sondern die Reihenfolge einzuhalten -- oder umgekehrt zu akzeptieren, dass der Store ein Erkundungsraum ist und der Record ihm nachlaeuft. Beides ist vertretbar, gemischt ist es nicht.
- **Die PROPOSED-Gruppe ruht auf sich selbst** (A4), mit einem echten Zirkel zwischen ADR-48 und ADR-52. Das ist die Folge davon, acht Records in einem Durchgang zu schreiben: jeder durfte auf den Stand verweisen, den die anderen gerade herstellten. Vorbeugung: ein Record je Entscheidung **und** je Sitzung annehmen, bevor der naechste ihn als geklaert zitiert.
- **Doppelt gefuehrte Aussagen** (A7, G7, Redundanz in 8.2) hat kein Werkzeug gefunden -- `adr_check` erkennt nur fast gleiche Titel. Sie fallen nur einem Leser auf, der den ganzen Bestand am Stueck liest. Genau dafuer ist dieser Durchgang da; die Vorbeugung ist, ihn regelmaessig zu fahren, nicht ein weiterer Check.
