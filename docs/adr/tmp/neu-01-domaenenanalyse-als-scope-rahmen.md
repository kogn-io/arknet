# neu-01: Die Domaenenanalyse ist der Scope-Rahmen, arknet baut einen Ausschnitt

- Status: Proposed (2026-08-23)

## Kontext

arknets Scope wird bisher am zuletzt gebauten Stand gemessen: was da ist, gilt als drin, und
alles daneben als draussen. Das hat zwei Folgen. Jede Erweiterung muss sich rechtfertigen,
als waere sie ein Ausbruch, statt als naechster Schritt behandelt zu werden. Und es gibt kein
Kriterium fuer die Reihenfolge -- ob etwas als naechstes kommt oder gar nicht, sieht gleich
aus.

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

- **Den Scope weiter am Bauzustand messen.** Verworfen -- erklaert eine Momentaufnahme zur
  Grenze und macht jede Erweiterung zum Rechtfertigungsfall.
- **Die Domaenenanalyse ins Repo aufnehmen und zum oeffentlichen Zielbild machen.** Verworfen
  -- sie beschreibt ein Produkt, das es nicht gibt. In einem oeffentlichen Repo ist das ein
  Versprechen, kein Rahmen.
- **Zielbild ohne Ausschnittsbegriff uebernehmen** (also: arknet ist all das). Verworfen --
  dann ist jeder unfertige Kontext ein Defizit statt eines offenen Schritts.
