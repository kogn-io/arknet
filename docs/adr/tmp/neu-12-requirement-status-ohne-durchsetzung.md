# neu-12: Requirement-Status ist ein unverbindliches Reifegrad-Signal, beidseitig setzbar

- Status: Proposed (2026-08-23)

## Kontext

Ein Requirement traegt einen Status. Die Frage ist, ob dieser Status etwas bewirkt: ob ein
`Accepted`-Requirement anders behandelt wird als ein `Proposed`-es, ob ein Statuswechsel
Bedingungen an andere Ressourcen stellt, ob eine Reihenfolge erzwungen wird.

Ein durchgesetzter Status verlangt ein Modell davon, was Erfuellung heisst -- welche Kanten
vorhanden sein muessen, welche Zustaende Nachbarn haben duerfen. Dieses Modell existiert nicht,
und es zu erfinden, waere eine Prozessentscheidung fuer fremde Teams.

## Entscheidung

1. Der Status ist ein unverbindliches Reifegrad-Signal fuer den lesenden Menschen. Keine
   Durchsetzung.
2. Die Tool-Oberflaeche erreicht `Proposed` und `Accepted`; die weiteren Ontologie-Werte
   bleiben ungenutztes Vokabular.
3. Ein Statuswechsel loest keine Konsequenz aus -- keine Kopplung an Erfuellungs- oder
   Dekompositionskanten, keine SHACL-Regel, kein Architekturtest, keine Reihenfolge zwischen
   dem Status eines Requirements und dem seiner Nachbarn.
4. Der Status ist **in beide Richtungen** setzbar. Eine Einbahnstrasse waere kein
   Reifegrad-Signal, sondern eine unvollstaendige Durchsetzung.

## Konsequenzen

**Positiv:** Kein Prozessmodell im Werkzeug, das fremde Teams nicht teilen. Der Status kostet
nichts und schadet nichts.

**Negativ:** Ein Signal ohne Konsequenz wird ueberlesen. Wer erwartet, dass `Accepted`
irgendetwas verhindert, wird enttaeuscht -- und die Erwartung ist naheliegend, weil das Wort
sie weckt. Wenn spaeter echte Konsequenzen kommen sollen, ist das eine neue Entscheidung, kein
Ausbau dieser.

## Alternativen

- **Status mit Durchsetzung.** Verworfen -- verlangt ein Erfuellungsmodell, das arknet nicht
  hat und nicht setzen sollte.
- **Kein Status.** Verworfen -- der Reifegrad ist eine reale Information, auch ohne Wirkung.
- **Einbahnstrasse Proposed nach Accepted.** Verworfen -- halbe Durchsetzung ohne Modell
  dahinter.
