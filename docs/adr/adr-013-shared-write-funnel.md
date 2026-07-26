# ADR-013: Geteilter Schreibtrichter fuer die kognio-rdf-Out-Adapter

- Status: Accepted (2026-07-26)
- Verwandt: ADR-007 (Modul und Write-Gate), ADR-011 (Revision je Schreibpfad -- gegen diesen
  Trichter zu lesen), ADR-005

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
