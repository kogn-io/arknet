# ADR-014: Revision als Concurrency-Token im geteilten Schreibtrichter

- Status: Accepted (2026-07-26)
- Verwandt: ADR-011 (Revisions-/PROV-O-Rahmen -- dessen Entscheidung 2 wird hier mechanisch
  eingeloest), ADR-013 (WriteFunnel -- dessen offengehaltener Revisions-Ansatzpunkt und dessen
  in Entscheidung 5 vertagte Sonderpfad-Frage werden hier entschieden), ADR-001,
  ADR-015 (praezisiert Entscheidung 4: der Service-seitige Merge uebergibt Feld-Deltas,
  keinen Objekt-Snapshot)

## Kontext

ADR-011 verlangt, dass jede Modellaenderung eine interne, immutable Revision erzeugt --
fuer jeden Schreibpfad ohne Ausnahme. ADR-013 hat den geteilten Schreibtrichter gebaut und
zwei Dinge bewusst offen gelassen: den Ansatzpunkt der Revisions-Erfassung (der Trichter
besitzt die Transaktion, implementiert die Erfassung aber nicht) und das Schicksal der
beiden Sonderpfade ausserhalb des Trichters (der Patch-`update` der ul-BC, das
`compareAndUpdate` der requirements-BC) -- als eigene, gegen ADR-011 zu pruefende
Entscheidung. Dieses ADR ist diese Entscheidung.

Die Untersuchung der Sonderpfade ergab: beide sind handgebaute optimistische
Nebenlaeufigkeitskontrolle ohne Versions-Token. `compareAndUpdate` liest den vollen
Ist-Zustand innerhalb der Schreibtransaktion und vergleicht ihn wertweise gegen den beim
Aufruf erwarteten Snapshot (boolean-Ergebnis fuer den Retry-Loop des Service); der
ul-Patch-`update` merged die Felder innerhalb der Adapter-Transaktion, ruft dafuer das
SHACL-Gate erst in der Transaktion und uebersetzt Commit-Konflikte in ein eigenes Signal.
Beide Formen existieren nur, weil es keine billige Antwort auf die Frage gibt, ob sich
eine Ressource seit dem Lesen geaendert hat.

Vier Kraefte formten die Entscheidung: (a) ADR-011s Ausnahmslosigkeit; (b) die bewusst
minimale Trichter-API (Signale als Supplier, Body als Consumer, keine Zusatztypen);
(c) der Lost-Update-Schutz muss erhalten bleiben -- Single-User heisst nicht
Single-Writer (parallele Sessions auf dem geteilten Daemon, ADR-001); (d) die Frage, ob
die Revisions-Mechanik in arknet oder als Hook in kognio-rdf lebt, war offen.

## Entscheidung

1. **Die Revision traegt eine Doppelrolle: PROV-O-Traeger und Concurrency-Token.** Je
   Ressource ist die juengste Revision (Head) im Store abfragbar; ein bedingter Write
   prueft den erwarteten Head innerhalb der Schreibtransaktion (Compare-and-Set).

2. **Die Revisions-Erfassung lebt im WriteFunnel.** Der Trichter schreibt Revision und
   Head-Fortschreibung atomar mit jedem Modell-Write in derselben Transaktion -- die
   Einloesung des in ADR-013 offengehaltenen Ansatzpunkts. Kein kognio-rdf-Hook: die
   Revision ist arknet-Policy (Vokabular, Head-Semantik, Signal-Uebersetzung) auf den
   neutralen `io.kogn.rdf`-Ports; das Modul bleibt RDF4J-frei.

3. **Der Trichter erhaelt eine bedingte Update-Variante.** Die erwartete Head-Revision
   kommt als Parameter, ein Mismatch wirft das BC-eigene Signal -- dieselbe
   Supplier-Bauart wie `notFound`. Der Body bleibt ein rueckgabewertfreier Consumer; die
   API waechst um einen Parameter, nicht um Modi oder Zusatztypen.

   **Reihenfolge-Auflage:** Entscheidung 3 darf nicht vor Entscheidung 4 ausgeliefert
   werden. Solange Schreibpfade am Trichter vorbeilaufen, steht der Head einer Ressource
   still, waehrend sich ihr Zustand aendert -- ein Compare-and-Set auf diesen Head winkt
   dann genau den Lost Update durch, den er verhindern soll, und ist damit schaedlicher
   als gar kein Token. Aus demselben Grund darf der Head vorher auch in keinem
   generischen Lesepfad erscheinen: ein Client, der ihn als Version liest, wuerde
   getaeuscht.

4. **Die Sonderpfade werden aufgeloest, nicht integriert.** `compareAndUpdate`
   degeneriert zum Head-Vergleich im Trichter; der Feld-Merge der ul-BC verlaesst die
   Adapter-Transaktion und wandert als Lesen-Mergen-bedingt-Schreiben (mit Retry) in den
   Service, womit das SHACL-Gate wieder vor der Transaktion laeuft. Nach der Migration
   laeuft jeder bewachte Schreibpfad durch den Trichter, und die in ADR-013
   festgeschriebene Konflikt-Asymmetrie endet: ein Head-Mismatch ist in allen Kontexten
   dasselbe Signalmuster.

5. **Agent-Aufloesung bleibt entkoppelt.** Die Revision traegt ihre PROV-O-Activity auch
   ohne aufgeloesten Agenten; die Anreicherung um eine Agent-Identitaet ist additiv und
   Nicht-Ziel dieses ADRs.

## Konsequenzen

**Positiv:**

- ADR-011s "ohne Ausnahme" wird strukturell statt konventionell: die Revisions-Erfassung
  ist eine Eigenschaft des Chokepoints, den kein bewachter Schreibpfad umgehen kann --
  dieselbe Qualitaet, die ADR-013 fuer validate-before-commit hergestellt hat.
- Der Lost-Update-Schutz wird einheitlich und billig: ein Head-Vergleich ersetzt den
  Voll-Snapshot-Vergleich (requirements) und den In-Tx-Merge (ul); die Trichter-API
  bleibt bei Signalen und Consumer-Body.
- Die Konflikt-Signale sind ueber alle Bounded Contexts vereinheitlicht.
- Die Aenderungsansicht aus ADR-011 (Revision gegen Revision) und die
  Commit-Verknuepfung docken an der Revision an -- an einem Ort, nicht an N
  Schreibpfaden.

**Negativ / bewusst deferred (YAGNI):**

- Speicherwachstum je Write (bereits in ADR-011 benannt) plus die Head-Fortschreibung
  als Zusatzkosten je Transaktion. Eine Kompaktierungsstrategie bleibt offen, bis die
  reale Wachstumsrate bekannt ist.
- Die Migration der Sonderpfade ist Bestandteil dieser Entscheidung, nicht optional:
  eine Revisions-Basis ohne sie liesse genau die stille Luecke bestehen, die ADR-011
  ausschliesst.
- Der Service-seitige Merge verlagert die Bewahrungslogik der ul-BC vom Adapter in den
  Service -- dort ist sie ohne Store testbar, aber der Adapter verliert die
  Eigenschaft, Patches ohne Beteiligung des Service abzuwickeln.
- Ein bedingter Write setzt voraus, dass der Aufrufer den Head kennt: Lesepfade muessen
  die Head-Revision mitliefern koennen. Wie weit sie ueber die internen Ports hinaus
  (etwa an MCP-Clients) exponiert wird, entscheidet der jeweilige Tool-Vertrag.

## Alternativen

- **Revision als reines Audit-Anhaengsel ohne abfragbaren Head.** Verworfen: das
  Concurrency-Token materialisiert sich nicht, die Sonderpfade blieben bestehen und
  brauchten je eine eigene Revisions-Erfassung -- "ohne Ausnahme" bliebe an zwei Stellen
  Konvention statt Struktur.
- **Sonderpfade unveraendert in den Trichter zwingen** (Function-Body, Konflikt-Parameter,
  In-Tx-Merge-Hooks). Verworfen: blaeht die API auf den kompliziertesten Fall auf --
  ADR-013s Ablehnungsgrund -- ohne die Ursache, das fehlende Token, zu beheben.
- **Eigene Revisions-Erfassung je Sonderpfad bei unveraendertem Trichter.** Verworfen:
  dupliziert die Logik, deren Zentralisierung ADR-013s Zweck war, und macht die
  ADR-011-Garantie von Disziplin je Adapter abhaengig.
- **Revisions-Mechanik als Hook in kognio-rdf.** Vorerst verworfen (revidierbar): die
  Revision ist ueberwiegend arknet-Policy auf neutralen Ports; ein Mechanik-/
  Policy-Schnitt lohnt erst mit einem zweiten Konsumenten ausserhalb arknets -- dieselbe
  Abwaegung wie in ADR-013.

## Nachtrag 2026-07-27 (#167): Ort der Retry-Schleife

Entscheidung 4 benennt als Ziel des ul-Feld-Merges den Service. Dieser Teil ihres Wortlauts
ist zu eng: verlangt ist, dass Lesen und SHACL-Gate vor der Schreibtransaktion liegen statt
darin -- nicht, dass die Schleife eine bestimmte Hexagon-Schicht bewohnt.

**Praezisierung:** Die Lesen-Mergen-bedingt-Schreiben-Schleife der ul-BC gehoert in den
Out-Adapter. Der Service reicht die Feld-Delta-Signatur unveraendert durch.

Begruendung: was hier gemerged wird, ist keine Feldrechnung auf einem Domaenenobjekt, sondern
Graph-Mechanik -- welche Praedikate ersetzt werden, welche unangetastet bleiben, welche dem
Gate nur behauptet und nie geschrieben werden. ADR-015 Entscheidung 3 weist genau diese
Bewahrungsarbeit dem Out-Adapter zu, Entscheidung 4 desselben ADR haelt die Feld-Delta-Semantik
am Out-Port fest. Eine Schleife im Service muesste beides ueber den Port ziehen: das
Head-Token als Parameter und die Praedikat-Auswahl als Aufrufer-Wissen. Der Port verlore seine
Feld-Semantik, und der Kern kaeme in Beruehrung mit einer Unterscheidung -- geschrieben gegen
nur behauptet --, die allein in der RDF-Repraesentation existiert.

ADR-015 Entscheidung 4 spricht vom "Service-seitigen Merge der ul-BC" und uebernimmt damit
denselben zu engen Wortlaut. Gemeint ist dort die Lage oberhalb der Schreibtransaktion, nicht
die Hexagon-Schicht; die Delta-Regel, die jene Entscheidung festschreibt, bindet den Merge
unabhaengig davon, wer ihn ausfuehrt.

Was Entscheidung 4 substanziell fordert, bleibt unberuehrt und gilt: das SHACL-Gate laeuft
wieder vor der Transaktion, jeder bewachte Schreibpfad laeuft durch den Trichter, und ein
Head-Mismatch ist in allen Kontexten dasselbe Signalmuster. Die requirements-BC behaelt ihre
Schleife im Service, weil sie dort eine fachliche Wiederholung des Lesens ist und kein
Wiederaufbau eines Graph-Patches. Die verbleibende Asymmetrie zwischen den beiden BCs liegt
damit im Ort der Wiederholung, nicht mehr im Konflikt-Signal -- und sie folgt aus dem
Zuschnitt des jeweiligen Out-Ports (Replace-by-Identity gegen Feld-Patch), nicht aus einer
freien Wahl.
