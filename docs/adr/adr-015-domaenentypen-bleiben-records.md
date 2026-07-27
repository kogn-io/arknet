# ADR-015: Domaenentypen bleiben Records -- kein graph-backed Domaenenobjekt

- Status: Accepted (2026-07-27)
- Verwandt: ADR-007 (kein Port ohne fachlichen Sinn im Core -- dessen Ablehnungsgrund traegt hier
  wieder), ADR-013 (Schreibtrichter), ADR-014 (praezisiert dessen Entscheidung 4), ADR-005
  (store-first als Quelle der nicht modellierten Praedikate)

## Kontext

Anlass ist die geplante generische `resource_update`-Fassade: sie braucht eine
Vorentscheidung ueber die Repraesentation, weil ein generischer Triple-Patch nur dann
fachlich validierbar waere, wenn ein Domaenenobjekt und seine RDF-Repraesentation dasselbe
sein koennen. Genau diese Frage entscheidet dieses ADR -- **nicht** die Bauweise der
Fassade selbst. Die bleibt offen: die hier gemessene Ein-BC-Sicht kann die Fassaden-Ebene
strukturell nicht erfassen, und dort laege der eigentliche Gewinn des Musters. Entschieden
wird sie bei der Design-Arbeit an der Fassade, gebunden an die Entscheidungen 1 bis 4 unten.

Das erwogene Muster stammt aus einem frueheren Projekt des Autors: Domaenenobjekte als
Java-Interfaces mit typisierten Gettern/Settern, deren Implementierung keine Felder haelt,
sondern einen RDF-Graphen -- jeder Zugriff liest oder schreibt Tripel. Das Objekt ist der
Graph; typisierte und generische Sicht sind dieselbe Repraesentation. Der erhoffte Gewinn:
die doppelte Uebersetzung im Out-Adapter (Objekt zu Kandidatengraph beim Schreiben,
Ergebniszeilen zu Objekt beim Lesen) faellt weg. Anders als im Original sollte der Kern
dabei RDF-frei bleiben -- reine Java-Signaturen, kein `asGraph()`, Implementierung und
Fabrik im Adapter, der sich seinen Graphen per Cast auf den eigenen Typ holt.

Die Frage wurde nicht argumentiert, sondern gemessen: ein vollstaendiger Pilot auf der
kleinsten BC (ubiquitous-language), gefahren gegen die in ihren Zusicherungen unveraenderte
Verhaltens-Testsuite. Vier Messfragen, vier Ergebnisse:

1. **Verschwindet die Mapping-Schicht?** Nein -- sie wandert und waechst. Der Out-Adapter
   selbst schrumpft deutlich (Repository 342 auf 197 Codezeilen), weil Zeilen-Gruppierung,
   Mehrwert-Aufloesung und die `OPTIONAL`/`BIND`-Rekonstruktion der Actor-Facette ersatzlos
   entfallen. Der Kandidatenbau verschwindet aber nicht, er zieht in die Fabrik; die
   Feld-zu-Praedikat-Abbildung landet in der graph-backed Implementierung. In Summe waechst
   der Produktionscode fuer identisches Verhalten von 427 auf 610 Codezeilen -- der groesste
   Einzelposten ist der Kern-Wertetyp, der von 16 Codezeilen (ein Record) auf 124 steigt
   (Interface, veraenderliche Implementierung, Konstruktions-Port).
2. **Bleiben die didaktischen Sofort-Ablehnungen erhalten?** Auf dem Schreibweg ja, auf dem
   Leseweg nicht. Die Store-Signale (Identitaets-, Code-, Nicht-gefunden-, Konflikt- und
   SHACL-Ablehnung) sind unberuehrt; die Wert-Invarianten des Typs gelten weiter, aber nur
   noch, weil jede Implementierung sie freiwillig aufruft -- der Kompaktkonstruktor eines
   Records konnte nicht umgangen werden, ein statischer Pruefer schon. Gelesene Objekte
   laufen ueberhaupt durch keine Invariante mehr, weil sie nicht mehr konstruiert, sondern
   umhuellt werden.
3. **ArchUnit gruen ohne Aufweichung?** Ja, alle vier Regeln, ohne eine Zeile daran zu
   aendern. Gruen bedeutet aber weniger als vorher: der treibende MCP-Adapter haelt jetzt
   Objekte, die einen RDF-Graphen tragen, ohne einen RDF-Typ zu nennen -- die Regeln lesen
   Bytecode-Abhaengigkeiten und koennen das prinzipiell nicht sehen.
4. **Wie fuegt sich der Factory-Port in die Port-Landschaft?** Schlecht. Er traegt keine
   Faehigkeit, die die Domaene von aussen braucht, sondern ist eine Konstruktionsnaht, die
   aus der Repraesentationswahl folgt -- genau die Portform, die ADR-007 abgelehnt hat.
   Dazu kommen zwei nicht typisierbare Vertraege: die Fabrik und das Repository muessen
   gepaart verdrahtet werden, und `create` akzeptiert nur Objekte der eigenen
   Implementierung. Der zweite Vertrag brach im Pilot in drei fremden Modulen -- zur
   Laufzeit, nicht beim Uebersetzen.

Ein Gewinn ist echt und liess sich belegen: ein per `CONSTRUCT` gelesener Subjektgraph
schreibt alles zurueck, was er gelesen hat, auch Praedikate, die kein Feld modelliert. Damit
waere ein Voll-Snapshot-Merge oberhalb des Adapters verlustfrei -- die Bauart, die ADR-014
Entscheidung 4 fuer die ul-BC vorsieht. Dieser Gewinn haengt jedoch am `CONSTRUCT`-Lesepfad,
nicht am Domaenen-Interface: derselbe Graph laesst sich adapterintern lesen, patchen und
zurueckschreiben, ohne dass der Kern seinen Typ aendert.

## Entscheidung

1. **Die Domaenentypen der Bounded Contexts bleiben Records.** Kein Domaenen-Interface mit
   graph-backed Implementierung, keine Setter auf Wertobjekten, keine
   Konstruktions-Out-Ports vom Zuschnitt einer `TermFactory`. Der Kompaktkonstruktor bleibt
   der Ort, an dem die Invarianten unumgehbar sind.

2. **Der Lesepfad eines Out-Adapters darf graphbasiert werden.** `CONSTRUCT` eines
   Subjektgraphen statt `SELECT` mit Zeilen-Gruppierung ist eine adapterinterne
   Entscheidung, die den Domaenentyp nicht beruehrt; sie ist erlaubt, wo sie die
   Gruppierungs- und Mehrwert-Behandlung einspart, und nirgends verlangt. Wo ein schlanker
   Projektions-Lesepfad genuegt, bleibt `SELECT` die guenstigere Wahl.

3. **Der Out-Port behaelt seine Feld-Semantik.** Die Bewahrung nicht modellierter Tripel ist
   Aufgabe des Out-Adapters -- als Graph-Patch innerhalb der Schreibtransaktion --, nicht
   Aufgabe eines Domaenenobjekts, das den Graphen durch die Anwendung traegt.

4. **Praezisierung zu ADR-014 Entscheidung 4:** Der Service-seitige Merge der ul-BC uebergibt
   Feld-Deltas ("nicht gesetzt heisst unveraendert"), niemals einen vollstaendigen
   Objekt-Snapshot. Ein Merge, der einen vollstaendigen Term im Service materialisiert und
   zurueckschreibt, ist ausgeschlossen: er stellt genau den Verlust wieder her, den der
   Patch-`update` behoben hat (ein store-first Concept traegt mehrsprachige oder mehrfache
   Literale, die ein einwertiges Feld nicht halten kann). Das graph-backed Objekt war die
   einzige gemessene Bauart, die einen Voll-Snapshot-Merge verlustfrei gemacht haette; mit
   Entscheidung 1 entfaellt sie, und der Delta-Weg wird verbindlich. Praezisiert durch ADR-014,
   Nachtrag 2026-07-27 (#167): gemeint ist oberhalb der Schreibtransaktion, nicht die
   Hexagon-Schicht; die Schleife wohnt im Out-Adapter.

## Konsequenzen

**Positiv:**

- Der Kern-Wertetyp behaelt, was ein Record kostenlos mitbringt und was der Pilot in beiden
  Implementierungen von Hand nachbauen musste: unumgehbare Invarianten, Unveraenderlichkeit
  und Wertgleichheit. Keine dieser drei Eigenschaften haengt kuenftig an der Disziplin einer
  Implementierung.
- Die ADR-007-Leitregel bleibt unverletzt: es entsteht kein Port im Core, der keine
  fachliche Faehigkeit benennt.
- Der Out-Port bleibt frei von unausgesprochenen Vorbedingungen. Ein Domaenenobjekt ist
  ueberall dasselbe, unabhaengig davon, welcher Adapter es gebaut hat -- die Eigenschaft, an
  der der Pilot ueber Modulgrenzen hinweg scheiterte.
- Der Teil des Musters, der wirklich Komplexitaet nimmt, bleibt verfuegbar: der
  `CONSTRUCT`-Lesepfad entfernt die Zeilen-Gruppierungsschicht, in der die
  Mehrsprachigkeits-, Zeilenvervielfachungs- und Blank-Node-Fehler gewohnt haben, ohne dass
  ein Kern-Typ dafuer aufgegeben wird.

**Negativ / bewusst deferred (YAGNI):**

- Die Feld-zu-Praedikat-Abbildung bleibt je Out-Adapter an zwei Stellen (Kandidatenbau und
  Lesepfad). Das ist der bewusst gezahlte Preis fuer einen technologiefreien Kern; die
  Doppelung faellt erst, wenn jemand einen Weg findet, sie zu buendeln, ohne den
  Domaenentyp zur Repraesentation zu machen.
- Eine generische Schreib-Fassade kann Praedikate, die kein Feld modelliert, nicht generisch
  bewahren. Bewahrung bleibt je Out-Adapter zu implementieren und je Kante zu begruenden --
  dieselbe Verantwortung, die der ADR-007-Nachtrag zu #65 bereits festgeschrieben hat.
- Die Bauweise der `resource_update`-Fassade bleibt offen, obwohl sie der Anlass dieses ADR
  war. Die Messung hat nur die Repraesentationsfrage beantwortet; ob die Fassade auf die
  BC-eigenen In-Ports dispatcht, generisch patcht oder den Mittelweg nimmt -- Dispatch auf
  einen schlanken per-BC-Patch-Port mit Graph-Mechanik adapterintern --, entscheidet die
  Design-Arbeit an der Fassade. Alle drei Formen sind mit den Entscheidungen 1 bis 3
  vertraeglich; ein Domaenen-Interface braucht keine davon.
- Entscheidung 4 verbietet dauerhaft eine Bauart, die naheliegt, sobald ein
  Concurrency-Token vorhanden ist ("lies das Objekt, aendere es, schreib es bedingt
  zurueck"). Wer sie einfuehrt, ohne die Delta-Semantik durchzureichen, erzeugt einen
  stillen Datenverlust, den kein Konflikt-Signal auffaengt.
- Entscheidung 2 erlaubt zwei Lesepfad-Bauarten nebeneinander. Das ist gewollt, kostet aber
  Einheitlichkeit: welcher Pfad wo gilt, muss der jeweilige Adapter begruenden.

## Alternativen

- **Graph-backed Domaenenobjekte (das gemessene Muster).** Verworfen: 43 Prozent mehr
  Produktionscode fuer identisches Verhalten, ein Kern-Wertetyp der seine drei
  Record-Eigenschaften verliert, ein Konstruktions-Port ohne fachliche Bedeutung und ein
  Cast-Vertrag, der erst zur Laufzeit bricht -- gegen einen Gewinn, der ueberwiegend auch
  ohne das Domaenen-Interface zu haben ist.
- **`asGraph()` am Core-Interface, wie im Ursprungsmuster.** Verworfen: ein RDF-Typ im Kern
  kippt die Eigenschaft, die ADR-007 und die ArchUnit-Regeln tragen -- und zwar sichtbar,
  was immerhin ehrlicher waere als die stille Variante, aber nicht besser.
- **Domaenen-Interface nur mit Gettern, Schreiben ueber einen Builder.** Verworfen: dann
  entfaellt genau der Pfad, der ueberhaupt gewinnt (Aenderung als Mutation am Graphen),
  waehrend die Kosten -- Interface statt Record, Konstruktions-Port, Cast -- vollstaendig
  bleiben.
- **Das Muster erst an einer groesseren BC messen (use-cases).** Verworfen: die kleinste,
  flachste BC ist der guenstigste Fall fuer das Muster. Ein Aggregat mit opaken
  Step-Knoten macht die Annahme "ein Subjekt ist ein Graph" teurer, nicht billiger; ein
  Nachmessen dort kann das Ergebnis nur verschlechtern.
