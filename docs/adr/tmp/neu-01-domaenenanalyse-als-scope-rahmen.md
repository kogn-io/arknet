# neu-01: Die Domaenenanalyse ist der Scope-Rahmen, arknet baut einen Ausschnitt

- Status: Proposed (2026-08-23) -- ersetzt ADR-017

## Kontext

ADR-017 hat ISO/IEC/IEEE 15288 als Scope-Rahmen benannt. Faktisch beschreibt es aber nicht
den Rahmen, sondern den Bauzustand: "arknet ist ein Design-Time-Dokumentationswerkzeug, kein
Betriebs-, Test- oder Beschaffungswerkzeug". Damit wurde der Stand von August zur Grenze
erklaert. Jede spaetere Erweiterung liest sich als Verstoss gegen den eigenen Rahmen, statt
als naechster Schritt.

Inzwischen liegt eine eigenstaendige Domaenenanalyse vor
(`arknet-management/research/domain-analysis/`): rund 110 Begriffe mit Quellenangabe,
Uebersetzungsregeln, Kollisionsregeln und dokumentierten Ausschluessen; daraus abgeleitet acht
Bounded Contexts mit Verantwortung, Datenhoheit und Kontextkarte; dazu ein Modul-Vorschlag.
Ihr Umfang ist bewusst gesetzt und beschreibt das Zielbild fuer arknet -- nicht den Scope
eines fremden Produkts.

## Entscheidung

Die Domaenenanalyse ist der Scope-Rahmen fuer arknet. arknet baut daraus einen bewusst
gewaehlten Ausschnitt -- heute die Kontexte 1 bis 3 (Product & Requirements,
Domaenenmodellierung, Architektur & Entscheidungen).

Alles Uebrige (Planung & Kapazitaet, Vorgangsverwaltung, Verifikation & Qualitaet, Delivery &
Release, Betrieb & Observability) gilt als **noch nicht gebaut**, nicht als **ausserhalb**.
Der Unterschied ist der Kern dieser Entscheidung: eine Scope-Frage wird kuenftig damit
beantwortet, wo im Zielbild etwas liegt und ob es an der Reihe ist -- nicht damit, ob es zum
zuletzt gebauten Stand passt.

ISO/IEC/IEEE 15288 bleibt Ordnungsrahmen **innerhalb** der Analyse (dort Abschnitt 0.3) und
verliert die Rolle als arknets Scope-Kriterium.

## Konsequenzen

**Positiv:** Scope-Fragen haben ein benanntes Zielbild statt einer Momentaufnahme. Jede
Erweiterung hat einen vorgesehenen Ort, und ihre Nachbarn sind bekannt. Der bisher
wiederkehrende Streit "gehoert das noch zu arknet" wird zur Reihenfolgefrage.

**Negativ:** Das Zielbild ist um ein Vielfaches groesser als das Gebaute. Damit steigt die
Versuchung, Ungebautes nach aussen als vorhanden oder als zugesagt darzustellen -- README,
Marketplace-Beschreibung und Produktvision muessen den Ausschnitt benennen, nicht das
Zielbild. Zudem liegt der Rahmen ausserhalb des Repos, ist also fuer Aussenstehende nicht
nachlesbar; das ist gewollt, macht ihn aber zu einer nur intern pruefbaren Referenz.

## Alternativen

- **ADR-017 unveraendert lassen.** Verworfen -- es erklaert den Ist-Zustand zur Grenze und
  produziert damit genau die Scope-Konflikte, gegen die es geschrieben wurde.
- **Die Domaenenanalyse ins Repo aufnehmen und zum oeffentlichen Zielbild machen.** Verworfen
  -- sie beschreibt ein Produkt, das es nicht gibt. In einem oeffentlichen Repo ist das ein
  Versprechen, kein Rahmen.
- **Zielbild ohne Ausschnittsbegriff uebernehmen** (also: arknet ist all das). Verworfen --
  dann ist jeder unfertige Kontext ein Defizit statt eines offenen Schritts.
