# ADR-021: arkreq:Goal wird ersatzlos entfernt

- Status: Accepted (2026-08-18)
- Related: ADR-020

## Kontext

`arkreq:Goal` ist seit seiner Deklaration eine leere Huelle: die Klasse steht samt
`goalStatement`, `subGoalOf` und `motivates` in der Requirements-Ontologie und traegt eine
eigene Shape, aber es gibt kein Aggregat, keinen Service und keine Tools -- eine Ressource,
die auf keinem Weg entstehen kann. Am Requirement existiert `motivatedBy` nur als
unaufgeloester IRI-Verweis (#322). Beides zusammen ist ein irrefuehrendes API: die
Tool-Oberflaeche nimmt einen Verweis auf eine Ressource entgegen, die es nie geben kann.

Mit ADR-020 laeuft die Herkunftsverankerung eines Requirements als direkte Kante zum
Stakeholder; eine Goal-Zwischenebene ist dafuer keine Voraussetzung.

Das Entfernen ist formal ein Breaking Change auf zwei Flaechen -- der Tool-Oberflaeche und
dem publizierten Vokabular. Praktisch faellt er in die billigste Phase, die es je geben
wird: die Ontologie ist pre-1.0 ohne bekannte externe Nutzer, Goal-Instanzen kann kein
Store enthalten (es gab nie einen Schreibpfad), und der einzige bekannte Konsument des
Parameters dokumentiert ihn lediglich als optional.

## Entscheidung

`arkreq:Goal` wird ersatzlos entfernt.

1. Die Klasse samt `goalStatement`, `subGoalOf` und `motivates` verschwindet aus Ontologie
   und Shapes; `motivatedBy` verschwindet aus Ontologie, Shapes, Domaenenmodell und
   Tool-Oberflaeche.
2. Es gibt keinen Ersatz und keine Migrationspflicht: eventuell vorhandene
   `motivatedBy`-Tripel in Bestandsstores bleiben als tote Daten liegen -- ohne Shape und
   ohne Leser stoeren sie nichts.

## Konsequenzen

**Positiv:** Ontologie und Tool-Oberflaeche behaupten nichts mehr, was nicht entstehen
kann. Die Traceability-Argumentation haengt kuenftig an der Stakeholder-Verankerung
(ADR-020) statt an einer nie erreichbaren Zwischenebene.

**Negativ / bewusst deferred (YAGNI):** Das Metamodell verliert seine explizite
WARUM-Ebene oberhalb des Requirements; ein uebergeordnetes Geschaeftsziel laesst sich nicht
mehr als eigene Ressource fassen. Braucht arknet spaeter eine Ziel-Ebene -- etwa wenn die
Richtung "Quelle fuer Testgenerierung" sie konkret verlangt --, entsteht sie als eigener,
neuer Schnitt mit eigenem Entstehungsweg; diese Vorarbeit ist bewusst nicht geleistet. Das
publizierte Vokabular verliert Begriffe -- fuer etwaige unbekannte externe Nutzer ein
Bruch, der pre-1.0 in Kauf genommen wird.

## Alternativen

- **Goal zum Aggregat ausbauen (Kette Stakeholder -> Goal -> Requirement).** Verworfen --
  deutlicher Aufwand fuer einen bisher rein hypothetischen Nutzen; seit der Deklaration hat
  kein Anwendungsfall eine Goal-Ressource verlangt.
- **Halbzustand behalten.** Verworfen -- eine Klasse ohne Entstehungsweg und ein Feld ohne
  Aufloesung sind ein irrefuehrendes API und genau der Befund, der zu entscheiden war.
- **Nur `motivatedBy` entfernen, die Klasse behalten.** Verworfen -- eine Goal-Klasse ohne
  einzige Kante zum uebrigen Modell waere noch toter als der heutige Halbzustand.
