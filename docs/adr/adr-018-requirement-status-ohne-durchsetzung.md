# ADR-018: Requirement-Status bleibt ohne Durchsetzung

- Status: Accepted (2026-08-06)

## Kontext

`arkreq:RequirementStatus` traegt sechs Ontologie-Werte (Proposed, Accepted, Implemented,
Verified, Rejected, Deprecated) ohne OSLC-RM-Aequivalent und ohne begruendendes ADR -- die
Requirements-Ontologie fuehrt den Lebenszyklus als arknet-eigene Ergaenzung ein, OSLC RM
ueberlaesst Status bewusst jedem RM-Tool. Von den sechs Werten sind ueber die Tool-Oberflaeche
nur zwei erreichbar (Proposed, Accepted). Ein Statuswechsel hat aktuell keinerlei Konsequenz:
keine Kopplung an die Erfuellungs- oder Dekompositionskanten eines Requirements, keine
SHACL-Regel, kein Architekturtest. Einziger Konsument ist die Report-Darstellung, die den Wert
als Text bzw. eingefaerbte Pille zeigt -- ein rein visuelles Signal ohne Verhaltensfolge.

Der Widerspruch trat im Brownfield-Selbstinterview der Requirements-BC zutage, beim Zuschnitt
des Use Case "Requirement akzeptieren": ein Statusmechanismus, der nichts bewirkt ausser
einzufaerben, laesst sich nicht sinnvoll als Use Case beschreiben, ohne eine Wirkung zu
unterstellen, die es nicht gibt.

Erwogen wurde ein Ausbau mit echten Konsequenzen -- etwa ein Gating, das die Erfuellungs- oder
Dekompositionskante eines Requirements an dessen Status und den Status verwandter Ressourcen
koppelt. Zwei Vorbedingungen dafuer fehlen im Modell:

- Das Requirements-Metamodell kennt keinen einzigen Eltern-Kind-Baum, sondern drei
  Kantentypen mit unterschiedlicher Kardinalitaet: Komposition (Requirement zu
  AcceptanceCriterion, UseCase zu Step; exklusives 1:N-Eigentum), Dekomposition (Requirement zu
  Requirement ueber `oslc_rm:decomposedBy`; ein echter Baum) und Erfuellung (UseCase bzw. Step
  zu Requirement ueber `oslc_rm:satisfies`/`stepRealises`; M:N, seit #266 nicht einmal
  verpflichtend). Eine Gating-Regel muesste vorab festlegen, welcher dieser Kantentypen
  ueberhaupt "Eltern-Kind" im Sinn der Regel meint.
- Die sechs Statuswerte sind unverbundene `owl:NamedIndividual`s ohne festgelegte Reihenfolge.
  Eine Regel wie "nicht akzeptierbar, solange eine verwandte Ressource noch nicht akzeptiert
  ist" setzt eine Ordnungsrelation zwischen den Statuswerten voraus, die es noch nicht gibt.

Eine Ruecksprung-Sperre -- ein einmal akzeptiertes Requirement laesst sich nicht mehr ohne
Weiteres zuruecksetzen -- waere zudem inhaltlich die klassische Baseline-Freeze-Semantik, die
die Requirements-BC bereits einmal erwogen und verworfen hat: sie setzt einen Apparat voraus
(Requirement-Versionierung, eine Change-Request-Ressource, eine genehmigende Rolle), den arknet
nicht hat, und ein Status ohne Weg zurueck waere ein Wert, den zu setzen irrational ist.

## Entscheidung

Requirement-Status bleibt ein unverbindliches Reifegrad-Signal fuer den lesenden Menschen, ohne
Durchsetzung.

1. Die Tool-Oberflaeche erreicht weiterhin nur Proposed und Accepted; die vier weiteren
   Ontologie-Werte bleiben ungenutztes Vokabular.
2. Ein Statuswechsel loest keine Konsequenz an anderen Ressourcen oder Kanten aus -- keine
   Kopplung an Erfuellungs- oder Dekompositionskanten, keine SHACL-Regel, kein Architekturtest.
3. Es gibt keine Reihenfolge- oder Gating-Beziehung zwischen dem Status eines Requirements und
   dem Status oder Vorhandensein verwandter Ressourcen.

## Konsequenzen

**Positiv:** Die Requirements-BC bleibt einfach -- kein neuer Erzwingungsmechanismus, keine
Vorwegnahme eines Change-Request-Apparats, den arknet noch nicht hat. Der Use Case "Requirement
akzeptieren" laesst sich ehrlich beschreiben: er setzt das Statusfeld, sonst nichts.

**Negativ / bewusst deferred (YAGNI):** Der Status bleibt kosmetisch und kann falsche
Verbindlichkeit suggerieren -- eine gruen gefaerbte Pille im Report sieht nach Freigabe aus, ist
aber keine. Vier der sechs Ontologie-Werte bleiben totes, unerreichbares Vokabular. Ein
spaeterer Ausbau (echte Konsequenzen, Status-Gating zwischen verwandten Ressourcen) bleibt
moeglich, setzt aber voraus, dass zuerst eine explizite Ordnungsrelation der Statuswerte
entsteht und -- sofern eine Ruecksprung-Sperre gewuenscht ist -- ein Change-Request-Apparat
(Versionierung, eigene Ressource, genehmigende Rolle). Diese Vorarbeit ist hier bewusst nicht
geleistet.

## Alternativen

- **Status ausbauen (echte Konsequenzen, volle Enum-Erreichbarkeit, Gating zwischen
  Requirement und abhaengigen Ressourcen).** Verworfen fuer jetzt -- setzt eine Ordnungsrelation
  der Statuswerte und eine Klaerung voraus, welcher Kantentyp "Eltern-Kind" bedeuten soll; ohne
  beides waere ein Gating willkuerlich.
- **`status`-Property ersatzlos entfernen.** Verworfen -- der informelle Reifegrad ist im
  Report bereits ein genutztes, wenn auch schwaches Signal; Entfernen waere ein Rueckschritt.
- **Enum auf die zwei erreichbaren Werte (Proposed/Accepted) reduzieren.** Verworfen fuer
  jetzt -- ob Implemented/Verified/Rejected/Deprecated spaeter Sinn ergeben, ist eine eigene
  Frage und nicht Teil dieser Zurueckstellung.
