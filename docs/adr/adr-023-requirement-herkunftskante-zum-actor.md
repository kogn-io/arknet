# ADR-023: Das Requirement bekommt eine Herkunftskante zum Actor

- Status: Accepted (2026-08-19)
- Related: ADR-017, ADR-020, ADR-021

## Kontext

Ein Requirement haengt heute an niemandem. Die Frage "wessen Bedarf war das?" hat kein
Ziel im Modell, und die Vollstaendigkeitsfrage keinen Bezugsrahmen -- damit sind *correct*
und *complete* aus der klassischen Validierungsliste nicht pruefbar (#322).

ADR-020 liefert das Ziel, auf das eine solche Kante zeigen kann; ADR-021 entfernt die
Zwischenebene, ueber die sie frueher haette laufen sollen. Offen bleibt, ob es die Kante
geben soll und wie verbindlich sie ist.

Die Elicitation-Sicht des Standardstoffs beschreibt daneben widerstreitende Interessen und
deren Aushandlung als Regelfall. Das ist eine eigene Modellebene: sie haelt fest, wessen
Interesse hinter einer Prioritaet steht und gegen wen sie durchgesetzt wurde.

ADR-017 zaehlt "Stakeholder Needs and Requirements Definition" zu den Prozessgruppen
innerhalb von arknets Scope-Rahmen; bislang traegt arknet davon nur die
Requirements-Haelfte.

## Entscheidung

Das Requirement erhaelt eine Kante zu dem Actor, aus dessen Bedarf es stammt.

1. Die Kante ist eine reine Herkunftsverankerung. Interessen, Aushandlung und Konflikte
   werden nicht modelliert.
2. Ein Requirement darf mehrere Herkuenfte haben -- mehrere Interessentraeger hinter einer
   Anforderung sind der Normalfall, nicht die Ausnahme.
3. Eine fehlende Herkunft wird gemeldet, aber nicht abgelehnt. Ein Actor-Typ, der
   ueblicherweise kein eigenes Interesse traegt, wird als Herkunft ebenfalls gemeldet und
   nicht abgelehnt -- ein System steht in der Praxis oft als Platzhalter fuer seinen
   Betreiber.

## Konsequenzen

**Positiv:** *correct* laesst sich pro Requirement gegen seine Herkunft pruefen, *complete*
gegen die erfassten Actors als Bezugsrahmen. Die Stakeholder-Haelfte der in ADR-017
bejahten Prozessgruppe ist eingeloest. Weil die Kante meldet statt abzulehnen, bleiben
bestehende Requirements ohne Herkunft aenderbar -- dieselbe Linie, die ADR-019 fuer den
Requirement-Status gezogen hat.

**Negativ / bewusst deferred (YAGNI):** Eine gemeldete, aber nicht erzwungene Herkunft ist
ein Reifegrad-Signal, keine Garantie -- ein Modell kann vollstaendig aussehen und
durchgehend herkunftslos sein. Wessen Interesse hinter einer Prioritaet steht und gegen
wen sie ausgehandelt wurde, bleibt ausserhalb des Modells; das ist additiv nachruestbar,
ohne die Verankerung anzufassen, und bisher hat es niemand verlangt.

## Alternativen

- **Herkunft ueber eine Ziel-Zwischenebene fuehren** (Actor -> Ziel -> Requirement).
  Verworfen mit ADR-021 -- die Zwischenebene war seit ihrer Deklaration nie erreichbar, und
  die Verankerung braucht sie nicht.
- **Die Herkunft zur Pflicht machen und ein Requirement ohne sie ablehnen.** Verworfen --
  bestehende Requirements waeren damit schlagartig ungueltig und nicht einmal mehr
  korrigierbar, und Herkunft ist beim Erheben oft erst der zweite Schritt.
- **Volles Interessen- und Verhandlungsmodell.** Verworfen fuer jetzt (revisitierbar) --
  eine eigene Modellebene ohne artikulierten Bedarf.
- **Genau eine Herkunft je Requirement erzwingen.** Verworfen -- es zwaenge zu einer
  willkuerlichen Wahl unter mehreren echten Interessentraegern.
