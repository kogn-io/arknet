# ADR-020: Stakeholder-Herkunftsverankerung im Requirements-Hexagon

- Status: Accepted (2026-08-18)
- Related: ADR-017

## Kontext

Die Standarddefinition des RE-Grundlagenstoffs fasst ein Requirement als Stakeholder-Need.
Zwei der klassischen Validierungskriterien (IEEE-830-/Davis-Liste) haengen direkt daran:
*correct* -- bildet jedes Requirement tatsaechlich einen Stakeholder-Need ab, oder ist es
selbst ausgedacht -- und *complete* -- fehlt kein Need. Ohne einen Stakeholder-Begriff im
Metamodell sind beide prinzipiell nicht pruefbar, weder im menschlichen Review noch
maschinell: die Frage "wessen Bedarf war das?" hat kein Ziel, und die Vollstaendigkeitsfrage
hat keinen Bezugsrahmen, gegen den sie gestellt werden koennte (#322).

arknet kennt bislang nur den Interaktionsakteur eines Use Case (`arkproc:Actor` mit seinen
Unterklassen, im Glossar als optionale Facette eines Begriffs gefuehrt) -- wer im Ablauf
handelt, nicht wessen Interesse das System bedient. Die Mengen ueberschneiden sich (ein
Endnutzer ist beides), sind aber nicht deckungsgleich: Regulator, zahlender Kunde oder
Wettbewerber sind Stakeholder ohne jede Systeminteraktion.

ADR-017 zaehlt "Stakeholder Needs/Requirements Definition" zu den Prozessgruppen innerhalb
von arknets Scope-Rahmen; bislang traegt arknet davon nur die Requirements-Haelfte. Ein
Stakeholder-Begriff ist damit keine Scope-Erweiterung, sondern das Einloesen einer bereits
bejahten Prozessgruppe.

Gegen einen eigenen Bounded Context spricht der offene, mit Belegen unterlegte Verdacht,
dass der bestehende Sechs-BC-Schnitt bereits zu fein ist -- einen siebten anzulegen,
waehrend das ungeklaert ist, wuerde die Lage verschaerfen.

## Entscheidung

Der Stakeholder wird als eigene Ressource im Requirements-Hexagon verankert, als reine
Herkunftsverankerung.

1. Stakeholder ist ein eigener Ressourcentyp -- der Interessentraeger, an dessen Bedarf ein
   Requirement haengt -- und wohnt als dritter Ressourcentyp neben Requirement und
   Constraint im bestehenden Requirements-Hexagon, mit eigenem Lebenszyklus ueber die
   Tool-Oberflaeche.
2. Das Requirement erhaelt eine direkte Kante zum Stakeholder als Herkunftsverankerung:
   jedes Requirement laesst sich der Frage "wessen Bedarf?" aussetzen, und die
   Vollstaendigkeitsfrage bekommt die erfassten Stakeholder als Bezugsrahmen.
3. Es bleibt bei der Herkunftsverankerung: kein Interessen-, Verhandlungs- oder
   Konfliktmodell -- wessen Interesse hinter einer Prioritaet steht und gegen wen sie
   ausgehandelt wurde, wird nicht modelliert.

## Konsequenzen

**Positiv:** *correct* und *complete* werden pruefbare Fragen -- pro Requirement gegen
seinen Stakeholder, in der Vollstaendigkeitsfrage gegen die erfasste Stakeholder-Menge. Die
Stakeholder-Haelfte der in ADR-017 bejahten Prozessgruppe ist eingeloest. Der Schnitt folgt
einer bestehenden Praezedenz (Constraint als zweiter Ressourcentyp desselben Hexagons); es
entsteht kein neuer Bounded Context und keine neue Modulgrenze.

**Negativ / bewusst deferred (YAGNI):** Dieselbe reale Partei kann doppelt existieren --
als Actor-Facette eines Glossarbegriffs und als Stakeholder -- ohne Identitaetsbruecke
zwischen beiden; eine Verbindung entsteht erst, wenn ein konkreter Bedarf sie verlangt. Das
Verhandlungs- und Konfliktmodell der Elicitation-Sicht (Interessen je Stakeholder,
Aushandlung von Prioritaeten) bleibt bewusst aussen vor -- es ist additiv nachruestbar,
ohne die Herkunftsverankerung anzufassen, und ein konkreter Bedarf wurde bisher nicht
artikuliert.

## Alternativen

- **Stakeholder als Facette nach dem Muster der Actor-Facette des Glossars.** Verworfen --
  ein Stakeholder ohne Systeminteraktion ist nicht per se ein Begriff der Ubiquitous
  Language; die Kopplung wuerde jeden Interessentraeger in das Sprachmodell zwingen und den
  Unterschied zwischen Interaktionsakteur und Interessentraeger verwischen.
- **Eigener Bounded Context.** Verworfen -- die Herkunftsverankerung teilt Lebenszyklus,
  Sprache und Tool-Oberflaeche mit den Requirements; ein siebter BC stuende zudem quer zum
  offenen Befund, dass der bestehende Schnitt bereits zu fein sein koennte.
- **Volles Verhandlungs-/Konfliktmodell.** Verworfen fuer jetzt (revisitierbar) -- eine
  eigene Modellebene ohne artikulierten Bedarf, additiv nachruestbar.
