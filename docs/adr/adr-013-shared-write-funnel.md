# ADR-013: Geteilter Schreibtrichter fuer die kognio-rdf-Out-Adapter

- Status: Accepted (2026-07-26)
- Verwandt: ADR-007 (Modul und Write-Gate), ADR-011 (Revision je Schreibpfad -- gegen diesen
  Trichter zu lesen), ADR-005, ADR-014 (loest den hier offengehaltenen Revisions-Ansatzpunkt
  und die in Entscheidung 5 vertagte Sonderpfad-Frage ein), ADR-015 (Bewahrung nicht
  modellierter Tripel bleibt Adapter-Verantwortung im Schreib-Body)

## Kontext

Die vier `*-adapter-kogniordf`-Out-Adapter (requirements, ubiquitous-language, use-cases,
bounded-context) duplizierten denselben handgeschriebenen Write-Skeleton: DatasetLifecycle-
acquire, Schreibtransaktion mit zwei `ASK`-Checks (Subject-Existenz -> BC-eigene
ResourceAlreadyExists-Ablehnung; `dcterms:identifier`-Existenz -> BC-eigene
Duplicate-Code-Ablehnung), der Schreib-Body, und die Uebersetzung eines Store-Commit-Konflikts
(#144) in dieselbe Duplicate-Code-Ablehnung -- bis hin zu wortgleich vierfach kopierten
erklaerenden Kommentaren. Mit der vierten BC war die Rule of Three ueberschritten; dieselbe
Schwelle, an der ADR-007 das SHACL-Write-Gate herausgezogen hat.

Vier Kraefte formten die Entscheidung: (a) die Duplikation selbst; (b) ADR-011 verlangt
kuenftig eine Revisions-/Provenance-Erfassung fuer jeden Schreibpfad ohne Ausnahme -- dafuer
fehlte ein benennbarer gemeinsamer Ort; (c) die `*-core`-Module muessen persistenzfrei
bleiben, Port-Signaturen und die BC-eigenen Exception-Typen als nach aussen geworfene Signale
unveraendert; (d) einzelne Schreibpfade weichen bewusst vom Skeleton ab (der Patch-`update`
der ul-BC mit eigener Konflikt-Uebersetzung, das `compareAndUpdate` der requirements-BC mit
Vergleichslesen in der Transaktion).

## Entscheidung

1. **Ein geteilter Schreibtrichter `WriteFunnel` in `arknet-persistence-support`.** Der
   Konsumentenkreis ist exakt der des Gates (die vier `*-adapter-kogniordf`), die
   ADR-007-Leitregel greift unveraendert: ein geteilter technischer Baustein bekommt ein
   Modul, wenn ihn mehrere Module brauchen, die einander nicht sehen duerfen -- und dieses
   Modul existiert bereits. Zwei Methoden, `create` (Subject neu + Code frei) und `update`
   (Subject muss existieren).

2. **Kontextunterschiede sind Parameter, kein Code im Trichter** -- dieselbe Bauart wie
   ADR-007 Entscheidung 3. Die BC-eigenen Exceptions kommen als Supplier-Signale herein, der
   eigentliche Schreib-Body (inklusive aller Bewahrungslogik, etwa der #65-Kanten oder des
   Step-Deletes der use-cases) als Callback auf der laufenden Transaktion. Der Trichter kennt
   keine BC, keinen Domaenentyp und keine Domaenen-Exception.

3. **Der Trichter ruft das Gate selbst und besitzt die Transaktion.** Validate-before-commit
   ist damit fuer jeden migrierten Pfad strukturell unumgehbar statt Konvention. Der
   Transaktions-Besitz ist zugleich der Punkt, an dem eine spaetere ADR-011-Revision atomar
   mit dem Modell-Write erzeugt werden kann -- diese Entscheidung haelt den Ansatzpunkt
   offen, implementiert ihn aber nicht.

4. **Die Commit-Konflikt-Uebersetzung (#144) gibt es nur im Create-Pfad.** `update` reicht
   die Store-Exception unveraendert weiter -- das war das Verhalten aller Adapter vor dem
   Trichter und wird festgeschrieben, nicht im Vorbeigehen veraendert.

5. **Die Sonderpfade bleiben ausserhalb des Trichters:** der Patch-`update` der ul-BC und
   `compareAndUpdate` der requirements-BC. Ob und mit welcher Begruendung sie migrieren, ist
   eine eigene, gegen ADR-011 zu pruefende Entscheidung -- nicht Teil dieser.

6. **Kein kognio-rdf-Hook.** Der Trichter ist arknet-Policy (Code-Semantik, Gate-Aufruf,
   Signal-Uebersetzung) auf den neutralen `io.kogn.rdf`-Ports (`dataset` + `terms`); das
   Modul bleibt RDF4J-frei, dieselbe ArchUnit-Absicherung wie beim Gate gilt mit.

## Konsequenzen

**Positiv:**

- Die Vierfach-Kopie des Skeletons samt seiner Kommentare ist beseitigt; die
  Skeleton-Semantik ist an genau einer Stelle testbar. Eine fuenfte BC erbt den gesamten
  geprueften Schreibweg durch eine POM-Zeile und zwei Methodenaufrufe.
- bc- und uc-Adapter halten Gate und Konflikt-Predicate nicht mehr selbst; ul und req nur
  noch fuer ihren jeweiligen Sonderpfad.
- ADR-011 hat einen benannten Ort: Revisions-Erfassung fuer die migrierten Pfade ist ein
  Eingriff in eine Klasse, nicht in vier.

**Negativ / bewusst deferred (YAGNI):**

- **Solange die Sonderpfade draussen sind, ist der Trichter nicht "jeder Schreibpfad" im
  Sinne von ADR-011.** Die Luecke ist damit benannt, nicht geschlossen: bevor ADR-011
  umgesetzt wird, muessen die Sonderpfade entweder migriert sein oder eine eigene
  Revisions-Erfassung erhalten -- sonst entstuende genau die stille Ausnahme, die ADR-011
  ausschliesst.
- **Der Trichter garantiert den Gate-Aufruf, nicht Gate-Vollstaendigkeit.** Validiert wird
  der uebergebene Kandidat; was der Body daneben schreibt (die bewahrenden Bypaesse aus dem
  ADR-007-Nachtrag zu #65), bleibt Adapter-Verantwortung -- unveraendert zur Lage davor.
- **Die Konflikt-Asymmetrie ist jetzt festgeschrieben:** ein im `update` verlorener
  SERIALIZABLE-Konflikt erreicht den Aufrufer als rohe, store-gefaerbte Exception (bc, uc),
  waehrend ul dafuer ein eigenes Signal hat und req den Fall ueber `compareAndUpdate`
  vermeidet. Eine Vereinheitlichung waere eine eigene Entscheidung mit Portfolgen.
- **`arknet-persistence-support` waechst.** Die Grabbelkisten-Warnung aus ADR-007 gilt
  weiter; der Trichter liegt noch im dortigen Themenkreis (Schreibpfad der
  kogniordf-Adapter), aber der Modulname traegt umso weniger, je mehr dazukommt.

## Alternativen

- **Duplikation belassen.** Verworfen: n=4, stabiler Code, und die Kommentare logen bereits
  vierfach dasselbe -- traege, nicht konservativ.
- **Abstrakte Basisklasse, Adapter erben den Skeleton.** Verworfen: Vererbung ueber
  Modulgrenzen koppelt die Adapter an eine Template-Methode und traegt BC-Wissen in die
  Hierarchie; Komposition mit Signal-/Body-Parametern haelt den Trichter BC-frei.
- **Neues eigenes Modul statt `arknet-persistence-support`.** Verworfen: identischer
  Konsumentenkreis, identisches Dependency-Profil -- ein zweites Modul truege nur einen
  zweiten Namen.
- **Gate-Aufruf beim Adapter belassen, Trichter nur als Tx-Skelett.** Verworfen: dann bleibt
  validate-before-commit Konvention je Adapter, und der Trichter waere kein Chokepoint --
  gerade die beiden Eigenschaften, die ihn tragen.
- **Mechanik als Hook in kognio-rdf.** Vorerst verworfen (revidierbar): der Trichter ist
  ueberwiegend arknet-Policy; ein Mechanik-/Policy-Schnitt wie beim `shacl`-Port lohnt erst,
  wenn ein zweiter Konsument ausserhalb arknets existiert.
- **Alle Schreibpfade sofort migrieren.** Verworfen: die Sonderpfade haben eigene
  Transaktions-Semantik (Patch-Delete-Insert mit Rueckgabewert, Vergleichslesen mit
  boolean-Ergebnis); sie unter denselben Trichter zu zwingen haette dessen API auf den
  kompliziertesten Fall aufgeblaeht, bevor der Nutzen belegt ist.

## Nachtrag 2026-07-27 (#173, kognio-rdf 0.2.x): der Guard-Read ist kein `ASK` mehr

Der Kontext-Abschnitt oben beschreibt das Skeleton als "Schreibtransaktion mit zwei
`ASK`-Checks". Beide Checks -- Subject-Existenz und `dcterms:identifier`-Freiheit in `create`,
Subject-Existenz in `update`/`compareAndUpdate` -- laufen seit kognio-rdf 0.2.x ueber
`DatasetTx#contains(graph, subject, predicate, object)`. Das ist keine Umbenennung, sondern
eine Haertung der #144-Invariante, und ihr Grund gehoert hierher:

Ein SPARQL-`ASK` als Guard auf IRIs, die der Store noch gar nicht kennt -- also genau der
"ist dieser brandneue Code schon vergeben?"-Fall -- ist unter `SERIALIZABLE` **nicht**
konfliktgeschuetzt. Gemessen auf RDF4J 6.0.0 + MemoryStore: in einem Zwei-Thread-Race, in dem
beide Guards vor beiden Writes laufen, committeten **beide** Transaktionen in 6 % bzw. 12 %
von je 1000 Laeufen (zwei Maschinen; die Rate ist timing-abhaengig, keine Konstante) und
hinterliessen genau das Duplikat, das der Guard verhindern soll. Ursache liegt in RDF4J, nicht
in arknet und nicht im Port: die Auswertung eines SPARQL-Statement-Patterns interniert seine
Konstanten in die Value-Registry des Stores; internieren zwei Threads dieselbe unbekannte IRI
gleichzeitig, behaelt jeder seine eigene Instanz, und die Konflikterkennung laeuft ueber die
(leere) Statement-Liste der falschen. Derselbe Race erkennt den Konflikt in 1000 von 1000
Laeufen, sobald der Guard ueber `RepositoryConnection#hasStatement` liest -- der Weg, den
`contains` nimmt. Belege und Entscheidung auf Port-Seite: kogn-io/rdf-core ADR-0008 samt
Issue #23.

**Festschreibung:** First-Insert-Uniqueness-Guards im Trichter benutzen `contains`, nicht
`ask`. Ein SPARQL-`ASK` bleibt fuer gewoehnliche Reads richtig; unzureichend ist allein diese
eine Verwendung. Wer den Trichter um einen weiteren Guard erweitert, der ueber noch nicht
existierende Ressourcen entscheidet, faellt unter dieselbe Regel.

Eine bekannte Ausnahme bleibt bestehen: `compareAndUpdate` liest den `arkprov:head` weiterhin
per SPARQL-`SELECT` (`WriteFunnel#readHead`), und im Fall `expectedHead == null` -- Subject
aelter als der Trichter, noch kein `arkprov:head`-Tripel -- entscheidet auch dieser Read ueber
eine noch nicht existierende Ressource. Der Race wird dort trotzdem erkannt, weil beide
Schreiber-Bodies replace-by-identity auf denselben Modell-Tripeln arbeiten und sich am Commit
ueberschneiden; der Head-Read ist also nicht die einzige Absicherung.

Bei derselben Gelegenheit wandert das Konflikt-Predicate in den Trichter. Entscheidung 4
setzte voraus, dass ein verlorener Commit als store-eigene, RDF4J-gefaerbte Exception
ankommt -- weshalb `isWriteConflict` je Adapter in der Repository-Factory gebaut wurde, der
einzigen Stelle, an der ArchUnit einem Adapter RDF4J erlaubt. Seit 0.2.x uebersetzt der
RDF4J-Transactor selbst in `io.kogn.rdf.dataset.ConcurrencyConflictException`
(kogn-io/rdf-core#30), einen neutralen Port-Typ; die vier bitgleichen Kopien hatten damit
keinen Existenzgrund mehr und sind zu `WriteFunnel.DEFAULT_WRITE_CONFLICT` zusammengefuehrt.
Der Konstruktor-Parameter bleibt: welche Exception ein verlorener Schreiber wirft, ist eine
Eigenschaft des Stores hinter dem Port (ADR-001, austauschbar), also ein Default und keine
Verdrahtung. Die Konflikt-Asymmetrie aus Entscheidung 4 bleibt unveraendert -- `update`
uebersetzt weiterhin nicht.
