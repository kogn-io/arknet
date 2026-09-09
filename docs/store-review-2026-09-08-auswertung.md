# Auswertung des Store-Reviews vom 2026-09-08

Durchgefuehrt am 2026-09-09. Grundlage ist `store-review-2026-09-08.md`, dort
Abschnitt 10 (18 Funde nach Wirkung) und Abschnitt 11 (Ursachen).

Der Auftrag war ausdruecklich nicht "Funde abarbeiten", sondern je Fund die
**Ursache** benennen und **was sie kuenftig verhindert**. Darum traegt jeder
Eintrag hier vier Zeilen: was gefunden wurde, woher es kommt, was es kuenftig
verhindert, und was tatsaechlich geschehen ist.

Sprache wie im Bericht: Deutsch, weil der geprueft Bestand ueberwiegend deutsch
zitiert wird. Das weicht von der Englisch-Regel fuer ausgelieferte Artefakte ab.

## 0. Was diese Auswertung nicht tut

Kein `adr_set_status`. Die Annahme der neun PROPOSED-Records ist eine
Einbahnstrasse (ACCEPTED friert die Textfelder ein, es gibt keinen Rueckweg) und
bleibt eine ausdrueckliche Entscheidung des Nutzers. Was sie braucht -- die
Annahmereihenfolge -- ist in Abschnitt 3 hergeleitet und steht als Vorlage auf
kogn-io/arknet#554.

## 1. Ausgang je Fund

### Fund 1 -- Drei ACCEPTED/PROPOSED-Paare widersprechen einander offen

Betrifft ADR-51/55, ADR-13/53, ADR-10/52, verbunden nur durch `relatedTo`.

- **Ursache:** `adr_supersede` verlangt **beide** Seiten ACCEPTED. Fuer eine
  Ablosung, die erst mit der Annahme wirksam wird, existiert keine Kante --
  `relatedTo` traegt die Arbeit ersatzweise mit.
- **Vorbeugung:** Eine ausdrueckbare Absicht macht die Annahmereihenfolge im
  Graphen lesbar statt nur in der Prosa. Ohne sie wiederholt sich der Zustand bei
  jedem Konsolidierungsdurchgang.
- **Ausgang:** Werkzeuglucke als #585 erfasst. Die drei Ablosungen selbst haengen
  an der Annahme (Abschnitt 3), nicht am Werkzeug: sobald beide Seiten ACCEPTED
  sind, traegt `adr_supersede` sie.

### Fund 2 -- Zirkel ADR-48 / ADR-52, keine gueltige Annahmereihenfolge

- **Ursache:** Acht Records in einem Durchgang geschrieben; jeder durfte auf den
  Stand verweisen, den die anderen gerade herstellten.
- **Vorbeugung:** Ein Record je Entscheidung **und** je Sitzung annehmen, bevor
  der naechste ihn als geklaert zitiert. Die Regel steht seit dem 2026-09-08
  ("ein PROPOSED-Record ist kein Schmierzettel").
- **Ausgang:** **Behoben.** Die Abhaengigkeit war nie symmetrisch: ADR-48 braucht
  die Kontextliste von ADR-52 wirklich (seine `decision` benennt die Kontexte),
  ADR-52 brauchte ADR-48 nicht -- seine sechs Kontexte sind ueber Sprachgrenzen
  begruendet, die Build-Gestalt stand nur als Vorgriff im `context`-Feld. Der
  Satz ist entfernt (de und en). Damit existiert eine gueltige Reihenfolge.

### Fund 3 -- Der Bestand hat die PROPOSED-Entscheidungen bereits vollzogen

`bc_list` fuehrt genau die sechs Kontexte, die ADR-52/53/54 erst vorschlagen;
ADR-13, den ADR-53 umkehrt, steht unveraendert auf ACCEPTED.

- **Ursache:** Store-Schreibaufrufe und Record-Annahme haben verschiedene
  Taktungen. Nicht "beschlossen und nicht befolgt", sondern "befolgt und nicht
  beschlossen".
- **Vorbeugung:** Keine Regel, sondern eine Wahl: entweder die Reihenfolge
  einhalten, oder ausdruecklich akzeptieren, dass der Store ein Erkundungsraum
  ist und der Record ihm nachlaeuft. Beides ist vertretbar, gemischt ist es
  nicht.
- **Ausgang:** Bleibt bis zur Annahme (Abschnitt 3). Der Zustand ist damit
  benannt, nicht behoben -- er verschwindet mit dem Statuswechsel.

### Fund 4 -- FR-3 widerspricht dem ausgelieferten Werkzeugvertrag

- **Ursache:** Es gibt keinen Ort, an dem das Tool-Schema gegen die Requirements
  gehalten wird, die es einloesen soll. #540 hat `req_update` geaendert, die
  Requirements sind mitgelaufen.
- **Vorbeugung:** Der Abgleich Tool-Schema gegen Requirements als wiederkehrender
  Punkt im Store-Review. Ein `store_check`-Kandidat ist es nicht -- die eine
  Seite ist Java-Code, die andere Store-Inhalt.
- **Ausgang:** #589. Der Befund ist gegen das ausgelieferte Schema verifiziert:
  `usesTermCodes` ersetzt Term-Kanten wholesale, und ein Voll-Ersetzen der
  Akzeptanzkriterien gibt es nicht (nur anhaengen / positionsweise korrigieren /
  entfernen). Das Modell hinkt dem Werkzeug hinterher, nicht umgekehrt.

### Fund 5 -- Kein Weg, eine Term-Verknuepfung wieder zu loesen

- **Ursache:** Dieselbe wie Fund 4. Das Werkzeug kann es seit #540; der
  Requirement-Korpus beschreibt es nicht.
- **Vorbeugung:** wie Fund 4.
- **Ausgang:** in #589 mit erfasst.

### Fund 6 -- Glossar-Zirkel und fehlender Term "Domain"

- **Ursache:** Jeder Begriff wurde gegen seine Nachbarbegriffe definiert, nicht
  gegen einen Anker ausserhalb. Der fehlende Oberbegriff "Domain" ist der Fall,
  den die Checkliste mit "grep die Vokabular-Quelle, nicht die Prosa" abfaengt:
  `arkddd:Domain` ist modelliert und `arknet-shapes.ttl` verlangt ein `partOf`
  darauf.
- **Vorbeugung:** Der Full-Set-Durchgang von `/arknet:req-interview` findet genau
  diese Klasse; die Ontologie ist die Quelle, nicht die Prosa.
- **Ausgang:** #590, Punkte 1 und 2. Bewusst nicht im Alleingang umgeschrieben:
  eine Definition zu ersetzen ist Bedeutungsarbeit, keine Kantenpflege.

### Fund 7 -- `broader:TERM-14` trugen nur die drei ADR-Begriffe

- **Ursache:** `term_add`/`term_update` nehmen `broader` entgegen, fordern es
  aber nie ein, und kein Check meldet eine halb gebaute Hierarchie. Wer drei
  Begriffe in einem Zug anlegt, setzt die Kante; wer einen nachtraegt, vergisst
  sie -- und beides sieht im Store gleich aus.
- **Vorbeugung:** `orphan_check` findet einen Begriff *ohne jede* Kante; ein
  Begriff, dem allein die Einordnung fehlt, faellt durch jedes Raster. Eine
  Regel dafuer ist #586 -- mit dem ehrlichen Vorbehalt, dass sie womoeglich
  nicht ohne Falschtreffer formulierbar ist.
- **Ausgang:** **Behoben.** `broader:TERM-14` gesetzt fuer TERM-10 (Requirement),
  TERM-11 (Constraint), TERM-13 (Term), TERM-19 (Bounded Context), TERM-27 (Use
  Case), TERM-28 (Acceptance Criterion), TERM-31 (Considered Option). TERM-20
  (Revision) bewusst nicht: eine Revision ist ein Stand *einer* Ressource, nicht
  selbst eine.

### Fund 8 -- BC-3 und BC-6 ohne Begriffskanten; 14 Terms ohne Kontext

- **Ursache:** Zwei verschiedene Dinge sehen gleich aus. Die 14 kantenlosen
  Begriffe sind der *gemeinsame* Bestand -- sie an alle sechs Kontexte zu
  haengen wuerde die Aussage "gemeinsam" vernichten. Das Metamodell kennt keinen
  Ort oberhalb der Kontexte.
- **Vorbeugung:** Solange "gehoert allen" nur als "gehoert keinem" darstellbar
  ist, kann kein Review entscheiden, ob ein kantenloser Begriff geteilt oder
  verwaist ist.
- **Ausgang:** #587 (Metamodell). Die Kanten fuer BC-3 und BC-6 selbst warten auf
  die Annahme von ADR-52 -- so schon in #572 Punkt 1 festgehalten.

### Fund 9 -- Alle 13 Context Relationships tragen denselben Typ

- **Ursache:** Die Wahl PUBLISHED_LANGUAGE fuer alle Kanten ist am 2026-09-08
  bewusst getroffen worden. Der Review widerspricht ihr nicht, haelt aber fest,
  dass ein Typ, der ueberall derselbe ist, nichts unterscheidet.
- **Vorbeugung:** Ein Pruefmodus, der einen *eingetragenen* Beziehungstyp gegen
  das Material haelt -- `/arknet:context-map` elicitiert heute nur.
- **Ausgang:** kogn-io/arknet-plugin#178.

### Fund 10 -- Die R1-Buendelung ist die Ursache der Abloeseketten

21 von 30 Records tragen mehr als eine Entscheidung.

- **Ursache:** Der Unabhaengigkeitstest ("eine Entscheidung je Record") wurde
  nicht **schriftlich** gefuehrt. Ein Test, den man im Kopf fuehrt, findet
  nichts: `adr_check` meldet hier null, der schriftlich gefuehrte Test des
  Reviews fand 21 Faelle.
- **Vorbeugung:** Den Test als Text ablegen (Q1/Q2 im Issue-Thread), bevor der
  Record geschrieben wird. Die Regel existiert bereits in `/arknet:adr`; was
  fehlte, war der Zwang zur Niederschrift.
- **Ausgang:** Kein Eingriff. Die betroffenen Records sind ueberwiegend ACCEPTED
  und werden nicht nachtraeglich zerlegt -- Buendelung ist ein Schoenheitsfehler,
  der stehen bleibt, bis eine Teilentscheidung wirklich kippt. Gilt fuer den
  naechsten Schnitt, nicht fuer den Bestand.

### Fund 11 -- ROLE-2 und ROLE-3 treiben keinen Use Case

- **Ursache:** Requirements und Use Cases wurden entlang des gebauten Werkzeugs
  erhoben, nicht entlang der Rollen. Der Vermittler (ROLE-4 Coding AI Agent) ist
  ueberall, die Fachrollen fast nirgends.
- **Vorbeugung:** Die Rollenliste als Ausgangspunkt der Erhebung nehmen, nicht
  als Nebenprodukt -- `role_usecase_matrix` zeigt die Luecke, sobald jemand sie
  liest.
- **Ausgang:** #590 Punkt 10, mit Verweis auf #579 und #577, die den Bestand
  erweitern.

### Fund 12 -- Null Constraints, waehrend fuenf Requirements Verhalten an ihnen zusagen

- **Ursache:** Der Ressourcentyp wurde gebaut und beschrieben, aber nie benutzt.
  Kein Requirement erzeugt einen Constraint, keins ist je an einer Instanz
  abgenommen worden.
- **Vorbeugung:** Eine Zusage an einem Typ ohne ein einziges Exemplar ist ein
  Signal, das kein Werkzeug gibt -- es faellt nur beim Lesen des ganzen
  Bestands auf. Kadenz, keine Regel.
- **Ausgang:** #590 Punkt 11.

### Fund 13 -- FR-5 verlinkt TERM-30 `Consequence` fuer ein Alltagswort

- **Ursache:** Die Erwaehnungserkennung ist literal und ganzwortig; "Konsequenz"
  im Satz "loest keine Konsequenz aus" trifft den Term. Die Kante wurde gesetzt,
  weil das Werkzeug sie vorschlug, nicht weil der Text den Begriff meint.
- **Vorbeugung:** Die Erwaehnungsliste von `orphan_check` ist ein Lesehinweis,
  kein Fund -- so seit #572 Punkt 10 auch in der Tool-Beschreibung.
- **Ausgang:** **Behoben.** FR-5 traegt jetzt TERM-10, TERM-11, TERM-13, TERM-15.
  Nebenbei behoben: FR-6 fuehrte "Group Actor" ein, ohne TERM-16 zu verlinken.

### Fund 14 -- Sieben `prefLabel` ohne `en`; deutsche Definitionen paraphrasieren das Label

- **Ursache:** Die Ein-Wort-Regel und die Erwaehnungserkennung wurden fuer einen
  einsprachigen Bestand entworfen und auf einen zweisprachigen angewandt. Formal
  bleibt die Regel erfuellt, ihr Zweck nicht -- und weil sie formal erfuellt ist,
  meldet kein Check etwas.
- **Vorbeugung:** Entweder `skos:altLabel` je Sprache in die Erkennung aufnehmen,
  oder die Zusage als das benennen, was sie dann ist: eine Regel fuer englische
  Prosa.
- **Ausgang:** #588 fuer die Erkennung. Die sieben fehlenden englischen
  `prefLabel` (TERM-27..33) waren der Restposten aus #572 Punkt 1 und sind
  nachgetragen -- `store_check LANGUAGE` meldet jetzt kein Feld mehr. Damit ist
  #572 geschlossen.

### Fund 15 -- Sechs Requirements ohne realisierenden Use Case, kein UC nennt ein NFR

- **Ursache:** dieselbe wie Fund 11.
- **Vorbeugung:** dieselbe wie Fund 11.
- **Ausgang:** #590 Punkt 10; der Bestand wird von #579 und #577 erweitert.

### Fund 16 -- Vier Records sind eher Bausteinsicht oder Status quo als Entscheidung

ADR-52, ADR-46, ADR-10 (Listen von Kontexten) und ADR-58 (bezeugt die eigene
Billigkeit: "ein Modulschnitt, kein Umbau des Kerns").

- **Ursache:** Die eigentliche Entscheidung ist jeweils das *Schnittkriterium*,
  die Aufzaehlung nur seine Anwendung -- und die Aufzaehlung veraltet mit dem
  naechsten Kontext.
- **Vorbeugung:** Q2 des Skills ("waere die Umkehr teuer?") ernst nehmen; wo die
  Antwort nein ist, gehoert die Sache in die Bausteinsicht
  (`docs/building-block-view.md`), nicht in einen Record.
- **Ausgang:** Dem Nutzer vorgelegt, nicht vom Reviewer entschieden -- steht in
  der Vorlage auf #554. ADR-46 und ADR-10 sind ACCEPTED und bleiben ohnehin
  unangetastet.

### Fund 17 -- TERM-23 Dataset und TERM-26 Component tragen Architekturdefinitionen

- **Ursache:** Ein Begriff wurde dort definiert, wo er gebraucht wurde -- und
  gebraucht wurde er in einer Architekturdiskussion. TERM-26 hat als einzige der
  27 Definitionen ueberhaupt keine Gattung.
- **Vorbeugung:** Die Trennlinie steht im Werkzeug selbst (`term_add`: "Domain
  meaning only -- no architecture, technology, or implementation decisions").
  Sie greift nur, wenn sie beim Schreiben gelesen wird.
- **Ausgang:** #590 Punkt 4.

### Fund 18 -- Die `affects`-Kanten folgen keinem erkennbaren Kriterium

- **Ursache:** Kanten wurden gesetzt, wo sie auffielen. Der Review konnte nicht
  wissen, dass die Frage am 2026-09-08 bereits entschieden wurde.
- **Vorbeugung:** entfaellt -- die Regel existiert.
- **Ausgang:** **Bereits entschieden** (#572 Punkt 7): `affectsContext` bleibt
  optional, ein ADR haengt nur an den Kontexten, die die Entscheidung gezielt
  bindet, "ohne Kante" ist kein Defekt, und die Altrecords werden nicht
  nachverlinkt. Damit sind ADR-13 und ADR-10 bewusst kantenlos. Kein
  Handlungsbedarf.

## 2. Was im Store veraendert wurde

Alles an PROPOSED-Records; kein ACCEPTED-Text angetastet, kein Status geaendert.

| Ressource | Aenderung | Grund |
|---|---|---|
| ADR-52 | `context`: Vorgriff auf die Build-Gestalt entfernt (de, en) | Fund 2 -- der Satz erzeugte den Zirkel und trug nichts: die sechs Kontexte sind ueber Sprachgrenzen begruendet |
| ADR-48 | `context`: Entwurfsgeschichte und Vorgriff auf die eigene Entscheidung entfernt; Option 2 ohne "Der erste Entwurf." (de, en) | Fund/A5 -- ein spaeterer Leser hat den nie gespeicherten Entwurf nicht |
| ADR-49 | `context`: Entwurfsgeschichte entfernt; Option 2 ohne "Der erste Entwurf." (de, en) | wie ADR-48 |
| ADR-53 | `context`: "Drei Umstaende haben sich seither geaendert" -> "sprechen gegen diese Einordnung" (de, en) | Beratungslage zu Sachbezug |
| ADR-54 | `context`: "Drei Orte waren bereits verworfen" ersetzt durch den einen Einwand, der sachlich traegt (de, en) | die drei Orte stehen als verworfene Optionen im selben Record |
| TERM-10, 11, 13, 19, 27, 28, 31 | `broader:TERM-14` gesetzt | Fund 7 |
| TERM-29 | `related`: TERM-30, TERM-31 | Fund 7 (G4) |
| TERM-30, TERM-31 | `related`: TERM-29 | Fund 7 (G4) |
| TERM-27 | `related`: TERM-10, TERM-11, TERM-13 ergaenzt | Fund 7 (G5) |
| TERM-10, TERM-11 | `related`: TERM-27 ergaenzt | Fund 7 (G5) |
| FR-5 | `usesTerm`: TERM-30 entfernt | Fund 13 |
| FR-6 | `usesTerm`: TERM-16 ergaenzt | 8.2 |
| TERM-27..33 | englisches `skos:prefLabel` nachgetragen | Restposten #572 Punkt 1 |

Die Kontextfelder sind bewusst **vor** der Annahme bereinigt worden: ACCEPTED
friert sie ein, und eine eingefrorene Entwurfsgeschichte ist der teuerste der
hier gefundenen Fehler.

### Pruefblock nach den Aenderungen

| Pruefung | Vor dem Durchgang | Danach |
|---|---|---|
| `store_check LANGUAGE` | 7 Felder ohne `en` (prefLabel TERM-27..33) | kein Feld fehlt |
| `store_check ROLE_TERM_DUPLICATE` | 0 | 0 |
| `orphan_check` Terme nie referenziert | 7 | **0** |
| `orphan_check` Mentioned-but-not-linked | 35 | **9**, ausschliesslich die nach #572 Punkt 10 dokumentierten Alltagswort-Falschtreffer |
| `orphan_check` Constraints ohne Bindung | 0 | 0 |
| `orphan_check` Requirements ohne UC | 6 | 6 (unveraendert; laeuft ueber #579, #577) |
| `adr_check` | 30 geprueft, 20 Fakten, 2 Verdachtsmomente | unveraendert |

Der neue Erwaehnungstreffer "FR-5 mentions Consequence" ist die erwartete Folge
von Fund 13: die falsche Kante ist geloest, das Alltagswort bleibt im Text
stehen, und die Erwaehnungsliste ist ein Lesehinweis, kein Fund.

Dass `adr_check` unveraendert meldet, ist ebenfalls erwartet: keine der
Aenderungen betraf eine Kante oder einen Status, und die 20 kantenlosen Records
sind nach #572 Punkt 7 bewusst kantenlos.

## 3. Annahmereihenfolge der neun PROPOSED-Records

Hergeleitet aus dem, was der Text eines Records als geklaert **voraussetzt** --
nicht aus `relatedTo`, das symmetrisch ist und nichts ordnet. Nach dem Fix an
ADR-52 ist die Ordnung zyklenfrei.

| # | Record | Setzt voraus | Warum |
|---|---|---|---|
| 1 | ADR-49 | nichts unter PROPOSED | Der Verweis auf ein API-Modul steht in einer verworfenen Option und begruendet sich selbst; ADR-58 haengt an ADR-49, nicht umgekehrt |
| 2 | ADR-52 | nichts unter PROPOSED | nach dem Fix; die sechs Kontexte sind ueber Sprachgrenzen begruendet |
| 3 | ADR-48 | 52, 49 | seine `decision` benennt die Kontexte; der Umweg entfaellt durch das Lesen ueber die Published Language |
| 4 | ADR-56 | 52 | "trifft alle sechs Kontexte" |
| 5 | ADR-53 | 56, 48 | Projektidentitaet im Shared Kernel; Modulschema kennt keine Komponente ohne Kontext |
| 6 | ADR-54 | 48, 49 (+ ADR-51, ACCEPTED) | eine Komponente, die die Published Language jedes Kontexts liest |
| 7 | ADR-55 | 54 | "Seit die Modellanalyse ein eigener Kontext ist" |
| 8 | ADR-57 | 48, 56 | zwei Kontexte mit je zwei Komponenten; Abgrenzung gegen den Shared Kernel |
| 9 | ADR-58 | 49 | "Seit ein Nachbar ausschliesslich ueber die Published Language gelesen wird" |

Danach, und erst danach, die drei Abloesungen, die `adr_supersede` beide Seiten
ACCEPTED verlangt: ADR-52 loest ADR-10 ab, ADR-53 loest ADR-13 ab, ADR-55 loest
ADR-51 ab. Die `relatedTo`-Kanten zwischen diesen Paaren koennen anschliessend
weg -- sie tragen dann nichts mehr.

Die Vorlage mit diesen Schritten steht als Kommentar auf kogn-io/arknet#554.

## 4. Woraus Issues geworden sind

| Nr | Gegenstand | Repo |
|---|---|---|
| #585 | `adr_supersede` verlangt beide Seiten ACCEPTED | arknet |
| #586 | halb gebaute Begriffshierarchie ist mechanisch unsichtbar | arknet |
| #587 | querschnittliches Vokabular hat im Metamodell keinen Ort | arknet |
| #588 | Erwaehnungserkennung sieht nur das Label, nicht seine Uebersetzung | arknet |
| #589 | FR-3/FR-4 beschreiben einen aelteren Aenderungsvertrag als `req_update` | arknet |
| #590 | offene Modellpunkte (Glossar, Requirements, Use Cases) | arknet |
| plugin#178 | Review-Modi fuer Bounded Context, Context Relationship, Role, Actor | arknet-plugin |

Geschlossen: **#572** (Inhalts-Review aus dem Store-Report) -- sein
Fertig-Kriterium ist mit dem Nachtrag der sieben englischen `prefLabel` und dem
Pruefblock oben erfuellt.

## 5. Was offen bleibt und warum

- **Die Annahme selbst.** Neun Statuswechsel, eine Einbahnstrasse, ausdrueckliche
  Nutzerentscheidung. Reihenfolge steht (Abschnitt 3), Vorlage auf #554.
- **Fund 16.** Ob ADR-52 und ADR-58 in dieser Form Records bleiben, ist eine
  Frage an den Nutzer, keine des Reviewers.
- **Vier ungeprueft gebliebene Ressourcentypen.** Bounded Context, Context
  Relationship, Role und Actor sind im Lauf vom 2026-09-08 **nicht geprueft**
  worden -- sie sind ungeprueft, nicht in Ordnung. Bis plugin#178 gebaut ist,
  bleibt jeder Store-Review an derselben Stelle blind.
