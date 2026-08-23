# neu-03: Actor ist ein Querschnitt, kein eigener Kontext

- Status: Proposed (2026-08-23) -- loest ADR-022 ab, bevor dieser gemergt ist

## Kontext

Seit Issue #336 ist `arkproc:Actor` eine eigenstaendige Ressource mit eigener
Tool-Oberflaeche (`actor_*`) statt einer Facette am Glossarbegriff. Diese Entscheidung ist
richtig und bleibt bestehen. Offen ist nur, ob der Actor damit auch ein eigener Kontext ist
-- ADR-022 (in PR #330, nicht gemergt) haelt genau das fest.

Der Zielschnitt (neu-01) fuehrt Akteure als **Querschnitt** und verwirft ein Actor-Modul je
Kontext ausdruecklich: es wuerde dieselbe Struktur vervielfachen. Er verbindet das mit einer
Zwangsbegrenzung -- der Querschnitt enthaelt ausschliesslich Identitaet, Rolle und Typ, keine
Berechtigungen, keine Praeferenzen, keine kontextspezifischen Attribute.

arknets Actor haelt diese Begrenzung heute bereits ein: Name, Beschreibung, Typ, Code, und
keine Kante zu einem anderen Kontext.

## Entscheidung

Actor wird als Querschnitt gefuehrt, nicht als eigener Modell-Kontext. Die Zwangsbegrenzung
des Zielschnitts gilt: Identitaet, Typ, Bezeichnung. Jedes kontextspezifische Attribut gehoert
in den konsumierenden Kontext, nicht an den Actor.

Dazu gehoert eine Unterscheidung, die im Zielbild sauber getrennt ist und in arknet leicht
verschwimmt:

- **Akteure des modellierten Systems** -- der Primaerakteur eines Use Case, ein Stakeholder
  des Nutzerprojekts. Das ist arknets heutiger `arkproc:Actor`.
- **Akteure des Werkzeugs** -- wer am Prozess arbeitet, Mensch oder KI-Agent, mit Prozessrolle
  und gemessenem Verbrauch. Das existiert in arknet nicht (Single-User-Client, siehe neu-04).

Beide duerfen beim spaeteren Ausbau **nicht** zu einem Typ verschmolzen werden. Es sind zwei
Begriffe unter einem Wort.

## Konsequenzen

**Positiv:** Kein eigener Hexagon fuer eine Ressource, die von mehreren Kontexten gelesen und
von keinem besessen wird. ADR-022 wird abgeloest, bevor er gemergt ist -- eine Entscheidung
weniger, die spaeter zurueckgenommen werden muss.

**Negativ:** Ein Querschnitt ist die teuerste Beziehungsform der Kontextkarte: jede Aenderung
trifft alle Konsumenten. Die Zwangsbegrenzung ist nicht technisch erzwungen, sondern muss im
Review durchgesetzt werden -- das erste kontextspezifische Attribut am Actor waere der
Anfang ihrer Aufloesung. Ein ArchUnit-Test kann die Modulabhaengigkeit festnageln, nicht die
Attributdisziplin.

## Alternativen

- **Actor als eigener Kontext (ADR-022).** Verworfen -- er hat keinen eigenen Sprachraum, nur
  einen eigenen Lebenszyklus, und der begruendet keine Kontextgrenze.
- **Actor-Facette am Glossarbegriff** (Zustand vor #336). Verworfen und bleibt verworfen --
  ein Akteur braucht weder Definition noch `TERM`-Code.
- **Je Kontext ein eigener Actor-Begriff.** Verworfen -- vervielfacht dieselbe Struktur; der
  Zielschnitt nennt das als Grund fuer den Querschnitt.
