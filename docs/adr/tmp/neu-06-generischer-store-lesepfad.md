# neu-06: Der generische Store-Lesepfad lebt im Composition Root, nicht in einem Kontext

- Status: Proposed (2026-08-23)

## Kontext

Ein Agent, der mit einem gefuellten Store arbeitet, braucht zwei Dinge, die keinem fachlichen
Kontext gehoeren: einen Ueberblick, was ueberhaupt drin steht, und den Rohzugriff auf eine
einzelne Ressource ueber ihren Bezeichner. Beides ist kontextuebergreifend und hat keine
eigene Fachsprache.

Baut man das als eigenen Kontext, entsteht ein Kontext ohne Domaene, der alle anderen kennen
muss. Baut man es je Kontext, entsteht dieselbe Lesefunktion mehrfach.

## Entscheidung

1. **Kein eigener Kontext und kein eigenes Hexagon.** Der generische Lesepfad lebt im
   Composition Root (`arknet-mcp`) als zwei read-only Tools: `store_overview` und
   `resource_get`.

2. **Eine einzige generische Statement-Abfrage speist beide.** Kein Typ-zu-Tool-Mapping --
   derselbe Code bedient jeden heutigen und kuenftigen Ressourcentyp gleich.

3. **Fachlich aufbereitete Sichten bleiben bei den Kontexten.** Der HTML-Report wird pro
   Kontext aus dessen Lese-In-Ports zusammengesetzt und faellt nur fuer alles Uebrige auf die
   generische Rohsicht zurueck. Der Agent-Digest bleibt generisch.

4. **Handle-Vertrag:** CURIE oder volle IRI sind verbindlich; eine blanke Business-Id wird als
   Komfort aufgeloest. Unbekannter Prefix oder eine ueber Kontexte hinweg mehrdeutige Id
   werden mit einer erklaerenden Meldung abgelehnt, nicht geraten.

## Konsequenzen

**Positiv:** Ein neuer Ressourcentyp ist im Ueberblick sichtbar, ohne dass dafuer Code
geschrieben wird. Die Kontexte bleiben frei von einer Nachbarschaftskenntnis, die sie
fachlich nicht brauchen.

**Negativ:** Der Composition Root traegt Logik, nicht nur Verdrahtung -- die Grenze zwischen
"Wiring" und "generischer Lesepfad" muss im Review gehalten werden. Und die generische Sicht
zeigt Rohtripel; sie ist eine Notfallsicht, keine Lesbarkeit.

## Alternativen

- **Eigener Kontext fuer den Store-Report.** Verworfen -- ein Kontext ohne Domaene, der alle
  anderen kennt.
- **Je Kontext ein eigener Ueberblick.** Verworfen -- dieselbe Funktion mehrfach, und kein Ort
  fuer die kontextuebergreifende Frage.
