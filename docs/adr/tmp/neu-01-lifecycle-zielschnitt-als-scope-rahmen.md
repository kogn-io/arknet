# neu-01: Ein benannter Lifecycle-Zielschnitt ist der Scope-Rahmen, arknet baut einen Ausschnitt

- Status: Proposed (2026-08-23)

## Kontext

arknets Scope wird bisher am zuletzt gebauten Stand gemessen: was da ist, gilt als drin, und
alles daneben als draussen. Das hat zwei Folgen. Jede Erweiterung muss sich rechtfertigen,
als waere sie ein Ausbruch, statt als naechster Schritt behandelt zu werden. Und es gibt kein
Kriterium fuer die Reihenfolge -- ob etwas als naechstes kommt oder gar nicht, sieht gleich
aus.

Was fehlt, ist ein Zielbild, das groesser ist als das Gebaute und trotzdem benannt.

## Entscheidung

Der Scope-Rahmen ist ein Zielschnitt ueber den gesamten Software-Engineering-Lifecycle in
acht fachlichen Kontexten:

1. Product & Requirements
2. Domaenenmodellierung (Ubiquitous Language, Kontexte, Kontextkarte)
3. Architektur & Entscheidungen
4. Planung & Kapazitaet
5. Vorgangsverwaltung
6. Verifikation & Qualitaet
7. Delivery & Release
8. Betrieb & Observability

Dazu quer zu allen acht: Akteursidentitaet (siehe neu-03).

arknet realisiert davon einen bewusst gewaehlten Ausschnitt. Welche Kontexte das zu einem
gegebenen Zeitpunkt sind, ist Bauzustand und steht nicht in diesem Record.

Die Kontextgrenzen folgen Sprachbruechen -- Stellen, an denen derselbe Sachverhalt beim
Uebergang andere Regeln bekommt (eine Anforderung wird zu einem priorisierbaren
Arbeitsvorrats-Element; ein fehlgeschlagenes Testergebnis wird zu einem Arbeitsauftrag). Wo
die Wahrheit ueber ein Konzept ausserhalb des Werkzeugs liegt -- Commits, Builds, Metriken --
entsteht kein eigener Kontext, sondern eine Uebersetzungsschicht im konsumierenden Kontext.

Begriffe und Grenzen sind gegen ISO/IEC/IEEE 29148 (Requirements Engineering),
ISO/IEC/IEEE 42010 (Architekturbeschreibung), ISO/IEC 25010 (Qualitaetsmerkmale),
ISO/IEC/IEEE 15288 (Lifecycle-Prozesse) sowie Evans und Vernon (DDD), Cockburn (Use Cases,
Ports & Adapters) und Nygard (Entscheidungsrecords) gepruefte, keine Eigenerfindungen.

**Der Kern dieser Entscheidung ist die Unterscheidung "noch nicht gebaut" gegen
"ausserhalb".** Eine Scope-Frage wird kuenftig damit beantwortet, wo im Zielschnitt etwas
liegt und ob es an der Reihe ist -- nicht damit, ob es zum zuletzt gebauten Stand passt.

Ausserhalb bleiben ausdruecklich: die Begriffe der **gebauten** Software (Authentifizierung,
Datenmodell, Infrastruktur -- arknet beschreibt den Prozess, nicht das Erzeugnis) sowie die
Rituale eines konkreten Vorgehensmodells. Ein Kontext bildet Iteration und Arbeitsvorrat
methodenneutral ab; Scrum ist eine Zuordnung darauf, kein Bestandteil.

## Konsequenzen

**Positiv:** Scope-Fragen haben ein benanntes Zielbild statt einer Momentaufnahme. Jede
Erweiterung hat einen vorgesehenen Ort, und ihre Nachbarn sind bekannt. Der wiederkehrende
Streit "gehoert das noch zu arknet" wird zur Reihenfolgefrage.

**Negativ:** Das Zielbild ist um ein Vielfaches groesser als das Gebaute. Damit steigt die
Versuchung, Ungebautes nach aussen als vorhanden oder zugesagt darzustellen -- README und
Produktbeschreibung muessen den Ausschnitt benennen, nicht das Zielbild. Ein Teil der
Kontexte ist ausserdem unbelegt in dem Sinne, dass fuer sie weder ein Nutzer noch ein
Anwendungsfall spricht; sie sind eine Absicht, keine Zusage.

## Alternativen

- **Den Scope weiter am Bauzustand messen.** Verworfen -- erklaert eine Momentaufnahme zur
  Grenze und macht jede Erweiterung zum Rechtfertigungsfall.
- **Nur die drei gebauten Kontexte benennen.** Verworfen -- dann fehlt genau die Information,
  die eine Reihenfolge ermoeglicht.
- **Den Zielschnitt an eine einzelne Norm binden** (etwa ISO/IEC/IEEE 15288 als
  Konformitaetsziel). Verworfen -- keine der Normen deckt Ubiquitous Language und
  Entscheidungsrecords zugleich ab; eine davon zum Ziel zu erklaeren, hiesse Kontext 2 oder 3
  aufzugeben.
