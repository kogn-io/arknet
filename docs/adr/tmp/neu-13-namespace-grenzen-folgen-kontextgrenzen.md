# neu-13: Namespace-Grenzen folgen den Kontextgrenzen, ohne mit ihnen gleichgesetzt zu werden

- Status: Proposed (2026-08-23) -- wird Accepted, sobald der Kontextschnitt aus neu-02 entschieden ist

## Kontext

arknets RDF-Namespaces und seine Kontextgrenzen sind auf zwei unabhaengigen Achsen
gewachsen. Drei Stellen, an denen sie sich heute widersprechen:

`arkreq:` traegt Requirement, Constraint und UseCase -- geschrieben von zwei getrennten
Kontexten. Ein Namespace, der ueber eine Kontextgrenze spannt, unterlaeuft diese Grenze
genau dort, wo sie nach aussen sichtbar wird: in der Published Language.

`arkproc:` heisst "process", traegt live aber ausschliesslich den Actor; Process, Step und
StateTransition sind geparkt. Der Name verspricht einen Gegenstand, den der Namespace nicht
hat -- wer ihn in einer Query liest, erwartet etwas anderes, als er bekommt.

Das Glossar schreibt reines SKOS und hat gar keinen eigenen arknet-Namespace. Das ist
richtig so -- SKOS ist das etablierte Vokabular fuer genau diesen Zweck -- zeigt aber, dass
Kontext und Namespace hier von vornherein keine Eins-zu-eins-Beziehung sind und auch keine
werden koennen.

Erschwerend: Namespace-IRIs sind veroeffentlichte Identitaet. Sie stehen in jedem Tripel
jedes bestehenden Stores. Eine Aenderung ist eine Datenmigration, kein Rename, und ihr Preis
steigt mit jeder Installation.

## Entscheidung

Namespace-Grenzen werden an den Kontextgrenzen ausgerichtet, aber nicht mit ihnen
gleichgesetzt. Es gelten zwei Regeln, und nur diese zwei:

1. **Kein Namespace spannt ueber zwei Kontexte.** Wo er es tut, wird entweder der Namespace
   geteilt oder der Kontextschnitt korrigiert -- je nachdem, welche der beiden Grenzen die
   fachlich richtige ist.
2. **Kein Namespace-Name behauptet mehr, als der Namespace traegt.** Ein Name, der einen
   Gegenstand verspricht, der nur geparkt existiert, wird auf das eingeengt, was live ist.

Ausdruecklich **nicht** entschieden wird eine Eins-zu-eins-Zuordnung von Kontext zu
Namespace. Ein Kontext darf mehrere Vokabulare fuehren, auch fremde -- das Glossar bleibt
SKOS, ohne dafuer einen eigenen arknet-Namespace zu bekommen.

Eine Namespace-Aenderung wird nur vorgenommen, wenn eine der beiden Regeln verletzt ist,
nie aus Symmetriegruenden. Der Preis der Migration ist der Grund fuer diese Zurueckhaltung.

## Konsequenzen

**Positiv:** Die Published Language widerspricht der Kontextgrenze nicht mehr. Der
schwerste der drei Fehlstaende loest sich dabei ohne jede Namespace-Aenderung auf: unter dem
Kontextschnitt aus neu-02 liegen Requirement, Constraint und UseCase in **einem** Kontext --
genau dem Inhalt von `arkreq:`. Regel 1 ist dort ab dann erfuellt, ohne dass eine IRI
angefasst wird.

**Negativ / bewusst offen:** `arkproc:` verletzt Regel 2 weiter, und die Behebung ist eine
Migration jedes bestehenden Stores. Der Preis steigt, solange nichts geschieht -- die
Registrierung des Namensraums laeuft, und jede weitere Installation vergroessert den
Bestand. Diese Entscheidung benennt den Verstoss, behebt ihn aber nicht; wann er behoben
wird, ist eine eigene Abwaegung zwischen Migrationsaufwand und Verwechslungsrisiko.

Regel 2 ist ausserdem ein Urteil, kein Test. Ob ein Name "mehr behauptet", laesst sich nicht
mechanisch pruefen und wird im Review entschieden -- anders als Regel 1, die sich aus dem
Schnitt ablesen laesst.

## Alternativen

- **Eins-zu-eins von Kontext zu Namespace.** Verworfen -- das Glossar muesste dafuer sein
  SKOS aufgeben und ein arknet-eigenes Vokabular fuer einen Zweck bekommen, fuer den ein
  etablierter Standard existiert. Der Preis waere Interoperabilitaet fuer Symmetrie.
- **Namespaces vollstaendig von den Kontextgrenzen entkoppeln.** Verworfen -- dann ist die
  Published Language beliebig, und ein Leser kann aus einer IRI nicht mehr schliessen, wer
  fuer den Begriff zustaendig ist.
- **Alles lassen wie es ist.** Verworfen -- `arkproc:` bliebe ein Name, der in jeder Query
  eine falsche Erwartung weckt, und beim naechsten Kontextschnitt faengt dieselbe Diskussion
  wieder von vorn an, weil kein Kriterium benannt ist.
