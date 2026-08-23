# neu-11: Die Revision traegt Provenance und dient zugleich als Concurrency-Token

- Status: Proposed (2026-08-23)

## Kontext

Aus neu-10 folgt, dass jeder Schreibvorgang eine Revision erzeugt. Unabhaengig davon braucht
ein Daemon, den mehrere Sessions gleichzeitig benutzen (neu-08), einen Schutz gegen den Lost
Update: zwei Schreiber lesen denselben Stand, beide schreiben, der zweite ueberschreibt den
ersten unbemerkt.

Beides braucht dasselbe: einen je Ressource abfragbaren, monoton fortgeschriebenen Zeiger auf
den letzten Stand. Zwei getrennte Mechanismen dafuer zu bauen, hiesse denselben Zeiger zweimal
zu fuehren -- mit der Gewissheit, dass sie irgendwann auseinanderlaufen.

## Entscheidung

1. **Die Revision hat eine Doppelrolle.** Sie ist PROV-O-Traeger und Concurrency-Token. Je
   Ressource ist die juengste Revision (der Head) abfragbar; ein bedingter Write prueft den
   erwarteten Head innerhalb der Schreibtransaktion (Compare-and-Set).

2. **Alle Schreibpfade laufen durch einen geteilten Trichter.** Er schreibt Modelldaten,
   Revision und Head-Fortschreibung atomar in derselben Transaktion. Der Trichter ist
   arknet-Policy auf neutralen Substrat-Ports und bleibt frei von der konkreten
   RDF-Bibliothek.

3. **Reihenfolge-Auflage, die diese Entscheidung tragen muss:** Der Head darf erst dann als
   Token angeboten oder gelesen werden, wenn **kein** Schreibpfad mehr am Trichter vorbeilaeuft.
   Solange einer vorbeilaeuft, steht der Head still, waehrend sich die Ressource aendert -- ein
   Compare-and-Set darauf winkt genau den Lost Update durch, den es verhindern soll, und ist
   damit schaedlicher als gar kein Token.

4. **Sonderpfade werden aufgeloest, nicht integriert.** Ein Feld-Merge gehoert oberhalb des
   Out-Ports als Lesen-Mergen-bedingt-Schreiben mit Retry, nicht als Sonderoperation in die
   Adapter-Transaktion.

## Konsequenzen

**Positiv:** Ein Mechanismus statt zweier. Der Nachweis, wer wann was geaendert hat, und der
Schutz gegen stilles Ueberschreiben fallen zusammen.

**Negativ:** Jeder Write kostet zusaetzliche Tripel und eine Head-Fortschreibung -- der Store
waechst deutlich schneller als das Modell. Der Trichter ist ein Engpass, durch den alles
muss; ein Fehler darin trifft jeden Kontext. Und Punkt 3 macht die Entscheidung
reihenfolgeabhaengig: teilweise ausgeliefert ist sie schlechter als gar nicht.

## Alternativen

- **Getrennte Versionsspalte neben der Provenance.** Verworfen -- derselbe Zeiger zweimal.
- **Optimistisches Sperren ueber Zeitstempel.** Verworfen -- Zeitstempel sind bei schnellen
  Folgeschreibungen nicht unterscheidbar.
- **Pessimistisches Sperren je Ressource.** Verworfen -- braucht Lock-Verwaltung und
  Timeout-Politik fuer einen Konflikt, der selten ist.
