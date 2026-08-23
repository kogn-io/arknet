# neu-10: Nachvollziehbarkeit ueber Commit-Provenance statt ueber einen diffbaren Export

- Status: Proposed (2026-08-23) -- ersetzt ADR-011

## Kontext

Store-first (neu-05) nimmt dem Modell das `git diff`. Die naheliegende Gegenmassnahme ist ein
sortierter, stabil serialisierter Datei-Export, den man mitversioniert.

Der Export loest das Problem aber nur scheinbar: er ist eine zweite Wahrheit, die auseinander
laeuft, sobald jemand sie editiert; er beantwortet "was hat sich geaendert", nicht "warum und
im Zusammenhang mit welcher Code-Aenderung"; und stabile RDF-Serialisierung ist ein
Dauerproblem, kein einmaliger Aufwand.

## Entscheidung

1. **Es wird kein diffbarer Datei-Export gebaut.** Datei-Ausgabe existiert nur als
   nicht-diffbarer Volldump zu Backup- und Portabilitaetszwecken -- nicht sortiert, nicht als
   Merge-Grundlage gedacht.
2. **Jede Modellaenderung erzeugt eine unveraenderliche Revision im Store.** Fuer jeden
   Schreibpfad ohne Ausnahme. Eine Ausnahme waere eine stille Luecke im Nachweis.
3. **Ein Git-Commit ist eine eigenstaendige Entitaet, mit Revisionen ueber eine n:m-Relation
   verknuepft** -- nicht als Attribut an der Revision. Eine Aenderung zieht mehrere Commits
   nach sich, ein Commit betrifft mehrere Revisionen.
4. **Die Verknuepfung wird als Commit-Message-Trailer gespeichert:** wiederholbarer Schluessel
   `Arknet:`, Wert ist ein arknet-Code. Der Trailer ist die einzige Quelle des Links; es gibt
   keinen zweiten Meldeweg.
5. **Commits werden aus der Historie gelesen, nicht per Hook erfasst.**

## Konsequenzen

**Positiv:** Die Verbindung zwischen Modelländerung und Code-Aenderung ist explizit statt
erschlossen. Kein zweiter Ort, an dem das Modell steht.

**Negativ:** Der Trailer wird vom Menschen oder Agenten geschrieben -- wer ihn vergisst,
erzeugt eine Luecke, die niemandem auffaellt. Die Nachvollziehbarkeit ist damit
disziplinabhaengig, waehrend ein Export es nicht waere. Und sie ist nur ueber arknet lesbar:
wer den Store nicht starten kann, sieht die Historie nicht.

## Alternativen

- **Sortierter, diffbarer Export.** Verworfen -- zweite Wahrheit, und beantwortet die
  Warum-Frage nicht.
- **Git-Hook statt Trailer.** Verworfen -- ein Hook ist lokal, nicht mitversioniert und auf
  jeder Maschine neu einzurichten.
