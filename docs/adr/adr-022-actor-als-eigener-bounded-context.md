# ADR-022: Actor wird ein eigener Bounded Context

- Status: Accepted (2026-08-19)
- Verwandt: ADR-020

## Kontext

ADR-020 fasst den Actor-Begriff breit. Damit stellt sich die Frage, die dort offen bleibt:
wo entsteht ein Actor?

Heute nirgends fuer sich. Ein Actor kann ausschliesslich als Anhaengsel eines
Glossarbegriffs entstehen -- derselbe SKOS-Concept traegt zusaetzlich einen Actor-Typ. Es
gibt keinen Weg, Actor zu sein, ohne Glossarbegriff zu sein. Nicht das Mehrfach-Typisieren
ist daran faul, sondern die Einbahnstrasse; sie hat drei nachweisbare Folgen:

- **Definitionszwang.** Ein Glossarbegriff verlangt eine Definition und einen
  Glossar-Code. Jeder Actor braucht damit einen Glossareintrag, auch wenn er keiner ist.
- **Namenskollision.** Die Aufloesung eines Actors laeuft ueber sein Label. Der Fachbegriff
  und der gleichnamige Actor koennen nicht beide so heissen -- zwei Ressourcen mit gleichem
  Label werden als mehrdeutig abgelehnt.
- **Klasse und Individuum sind ununterscheidbar.** Ein Begriff, der eine Metamodell-Klasse
  erklaert, kann denselben Actor-Typ tragen wie ein konkreter Actor. Nichts hindert daran,
  und im arknet-eigenen Modell ist es passiert.

Gegen einen eigenen Bounded Context steht der offene, mit Belegen unterlegte Verdacht,
dass der bestehende Schnitt bereits zu fein ist. Dafuer steht, dass Actor eine eigene
Sprache und einen eigenen Lebenszyklus hat und von drei Seiten gebraucht wird: von den Use
Cases, von den Requirements und -- sofern der Anschluss aus ADR-020 spaeter eingeloest
wird -- vom schreibenden Agenten.

## Entscheidung

Actor wird ein eigener Bounded Context mit eigenem Lebenszyklus und eigener
Tool-Oberflaeche. Ein Actor darf zusaetzlich Glossarbegriff sein, muss es aber nicht; die
bisherige Facette am Glossarbegriff entfaellt ersatzlos.

## Konsequenzen

**Positiv:** Die drei Folgen der Einbahnstrasse verschwinden: ein Actor braucht keine
Glossardefinition, er kollidiert nicht mehr mit einem gleichnamigen Fachbegriff, und ein
Begriff, der eine Klasse erklaert, ist kein Actor mehr. Der Use-Cases-Hexagon bleibt von
dem Traegerwechsel unberuehrt, weil seine Actor-Referenz seit jeher nur die opake
Ressourcen-Identitaet haelt und keinen glossarspezifischen Typ -- die Kanten ueberstehen
den Wechsel unveraendert.

**Negativ / bewusst deferred (YAGNI):** Der Schnitt fuegt einen weiteren Bounded Context
hinzu, obwohl offen ist, ob der bestehende schon zu fein ist -- die Frage wird damit nicht
beantwortet, sondern um einen Fall erweitert. Das Entfernen der Facette ist ein Breaking
Change auf der Tool-Oberflaeche und trifft die Skills, die sie dokumentieren. Bestehende
Modelle tragen Glossarbegriffe mit Actor-Typ, die bereinigt werden muessen; die
Entscheidung erzwingt damit eine Loeschfaehigkeit fuer Ressourcen.

## Alternativen

- **Facette am Glossarbegriff behalten und nur den Begriff breiter fassen.** Verworfen --
  loest zwar die Doppelexistenz, laesst aber alle drei Folgen der Einbahnstrasse bestehen;
  jeder Interessentraeger wuerde weiterhin in das Sprachmodell gezwungen.
- **Actor als weiterer Ressourcentyp im Requirements-Hexagon**, nach dem Vorbild des
  Constraints. Verworfen -- die Use Cases brauchen den Actor als Pflichtangabe und haengen
  damit am staerkeren Bedarf; sie wuerden zu Gast beim schwaecheren Konsumenten, und die
  Kante waere nur verschoben, nicht reduziert.
- **Actor als weiterer Ressourcentyp im Ubiquitous-Language-Hexagon.** Verworfen -- es
  erhaelt zwar die bestehende Kanten-Topologie und trennt den Traeger sauber, aber das
  Modul traegt dann zur Haelfte etwas, das seine Ubiquitous Language nicht ist.
- **Den Kontext "Identity" nennen und den schreibenden Agenten mit aufnehmen.** Verworfen
  -- der Begriff ist bereits durch die Projekt-Identitaet belegt, und der Name wuerde die
  in ADR-020 ausdruecklich offen gelassene Frage beantworten, ob modellierter Actor und
  schreibender Agent dasselbe sind.
