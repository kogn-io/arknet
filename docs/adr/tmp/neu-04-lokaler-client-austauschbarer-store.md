# neu-04: Lokaler Single-User-Client, Store hinter domaennahen Out-Ports austauschbar

- Status: Proposed (2026-08-23) -- ersetzt ADR-001

## Kontext

arknet laeuft auf dem Rechner eines einzelnen Autors und schreibt in einen lokalen Store.
Die Frage ist, ob dieser Umstand bis in die Kontext-Cores durchschlaegt -- ob dort also
Nutzer, Mandant oder Team vorkommen -- und ob ein spaeteres Remote-Backend einen Umbau der
Cores erzwingen wuerde.

Das Zielbild (neu-01) sieht Teams aus Menschen und Agenten vor. Ein Team-Begriff, der heute
in die Cores geschrieben wird, waere Spekulation; ein Store-Zugriff, der heute an RDF
gebunden wird, waere spaeter nicht mehr aufzuloesen.

## Entscheidung

1. **arknet ist ein lokaler Single-User-Client.** Die Kontext-Cores kennen keinen Auth-,
   Mandanten- oder Team-Begriff. Nutzeridentitaet und Zugriffskontrolle sind kein Belang von
   arknet -- weder heute noch als vorbereiteter Haken.

2. **Persistenz laeuft ausschliesslich ueber domaennahe Out-Ports.** Ein Port nimmt und liefert
   Domaenentypen (`Requirement` rein, `Requirement` raus) -- keine Triples, keine RDF-Typen,
   keine Query-Sprache in der Signatur. Der heutige Adapter setzt auf kognio-rdf auf; ein
   Backend-Wechsel bleibt damit ein Adapter-Austausch.

3. **Die ProjectId laeuft durch In- und Out-Ports als Routing-Schluessel** und waehlt das
   Dataset. Eine Installation fuehrt mehrere Projekte (neu-09).

Der Multi-User-Fall wird **nicht** vorbereitet. Sollte er kommen, traegt ihn das dann
gewaehlte Backend, nicht arknets Cores.

## Konsequenzen

**Positiv:** Die Cores bleiben klein und frei von Infrastrukturbegriffen. Der Store ist
austauschbar, ohne dass eine Zeile Fachlogik faellt.

**Negativ:** Domaennahe Ports kosten Uebersetzung im Adapter -- ein `CONSTRUCT`, das direkt
ein Aggregat laedt, waere kuerzer. Und die Entscheidung gegen jede Auth-Vorbereitung heisst:
wenn Mehrbenutzerbetrieb je kommt, ist er ein echter Umbau, kein Einschalten.

## Alternativen

- **Team-/Auth-Begriffe schon jetzt in den Cores.** Verworfen -- ein Modell fuer einen
  Betrieb, den es nicht gibt.
- **Store direkt als RDF-Port** (Triples in der Signatur). Verworfen -- macht jeden
  Backend-Wechsel zu einem Fachlogik-Umbau.
