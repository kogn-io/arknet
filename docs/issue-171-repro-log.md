# Issue #171 -- Repro-Protokoll (Schritt 1: Messen)

Status: reines Mess-Protokoll, kein Fix. Kein Produktionscode wurde veraendert.

## Ausgangslage

Die vier Real-Store-Concurrency-Tests bewachen die Invariante "zwei parallele Racer
bekommen verschiedene Business-Codes" ueber real gestartete Threads plus
`CyclicBarrier`/`CountDownLatch`, die die Ueberlappung der beiden Schreib-Transaktionen
deterministisch erzwingen (siehe Klassen-Javadoc der Tests). Laut Auftrag ist der
SERIALIZABLE-Commit-Konflikt zweimal unter Last NICHT gefeuert; beide Racer bekamen
denselben Code. Nie reproduzierbar gewesen.

Betroffene Tests:
- `BoundedContextServiceRealStoreConcurrencyTest`
- `UseCaseServiceRealStoreConcurrencyTest`
- `RequirementServiceRealStoreConcurrencyTest`
- `TermServiceRealStoreConcurrencyTest`

## Code gelesen (kein Raten)

- `arknet-persistence-support/src/main/java/de/hauschel/arknet/persistence/WriteFunnel.java`
  -- `create()` faengt den store-seitigen Commit-Konflikt (`isWriteConflict`, Default
  `ConcurrencyConflictException`) und uebersetzt ihn in dasselbe `duplicateCode`-Signal
  wie der synchrone `contains`-Guard.
- `arknet-shared-kernel/src/main/java/de/hauschel/arknet/kernel/CodeAssignment.java`
  -- `createRetryingOnCodeCollision`: faengt das BC-eigene `Duplicate*CodeException`-Signal,
  berechnet den Code neu, versucht erneut (bis `DEFAULT_MAX_ATTEMPTS = 20`).
- Alle vier `*RealStoreConcurrencyTest`-Klassen: `GuardedLifecycle`/`GuardedHandle`/
  `GuardSyncTx` erzwingen die Ueberlappung deterministisch -- ein `CyclicBarrier(2)` haelt
  beide Schreiber an, bis BEIDE ihren `contains`-Uniqueness-Guard bereits bestanden haben,
  ein `CountDownLatch` haelt den Verlierer an, bis der Gewinner fertig committed hat. Die
  Reihenfolge der beiden Commits ist damit durch Synchronisationsprimitive erzwungen, nicht
  durch reines Thread-Timing.

**Wichtige Beobachtung fuer die Bewertung eines Nicht-Fehlschlags:** Da `CyclicBarrier`/
`CountDownLatch` blockierende, korrektheitserhaltende Synchronisationsprimitive sind, ist
die Interleaving-Reihenfolge selbst unter beliebigem Scheduling/CPU-Last GARANTIERT gleich
-- CPU-Last kann bestenfalls VERLANGSAMEN, wann die Barriere/das Latch erreicht wird, nicht
die Reihenfolge selbst aendern. Ein Fehlschlag dieser vier Tests koennte demnach nur aus
einem der folgenden Orte kommen, nicht aus reinem Test-Thread-Scheduling:
1. Der Store (RDF4J `SailRepository` unter `SERIALIZABLE`) erkennt den echten Schreib-
   Konflikt in einer seltenen Konstellation NICHT (Store-Bug bzw. Grenzfall der Isolation).
2. `WriteFunnel.isWriteConflict`/`ConcurrencyConflictException`-Uebersetzung greift nicht in
   allen tatsaechlichen Konfliktformen (z.B. eine andere Exception-Unterklasse/-Kette, die
   `DEFAULT_WRITE_CONFLICT` nicht erkennt).
3. `CodeAssignment`s Retry berechnet den neuen Code gegen einen Stand, der selbst schon wieder
   stale ist (waere aber ein dritter, noch selteneren Zustand mit drei Beteiligten).

Das heisst: diese vier Tests sind eher ein Test des STORE-Verhaltens unter forcierter
Ueberlappung als ein Test des Thread-Schedulings -- ein Repro durch reine CPU-Last auf
Testebene ist a priori unwahrscheinlich; es sei denn, CPU-Last aendert das Verhalten der
zugrundeliegenden RDF4J-Transaktionen selbst (z.B. GC-Pausen mitten in einer noch offenen
Transaktion, die einen internen Snapshot-Vergleich beeinflussen).

## Messaufbau

- Baurechner: 16 Kerne (AMD Ryzen 7 5700U), JDK 25 (`openjdk 25 2025-09-16`).
- Build/Test ausschliesslich shell-`mvn` (`mvn -o ... install`), kein JDT-MCP (kein
  `--release 25`).
- Repro-Harness: eine Wegwerf-Klasse `RepeatRunner.java` ausserhalb des Repos, in einem
  Sitzungs-Arbeitsverzeichnis, das mit der Sitzung verschwindet. Wer diese Messung wiederholen
  will, baut sie aus der folgenden Beschreibung neu -- sie ist bewusst klein genug dafuer und
  wurde nicht ins Repo gelegt, weil sie kein Testartefakt ist. Sie nutzt die
  JUnit-Platform-Launcher-API
  (`junit-platform-launcher:6.1.1`, aus `~/.m2`), um dieselbe Testklasse N-mal zu wiederholen
  und jeden Fehlschlag (Testname, Iteration, Message, vollstaendiger Stacktrace) in eine
  Logdatei zu schreiben, ohne den Maven/JVM-Neustart-Overhead pro Wiederholung (~12s bei
  `mvn -Dtest=...`) zu zahlen.
- Klassenpfad: `target/classes` + `target/test-classes` der vier Adapter-Module + ihrer
  `*-core`-Module + `arknet-shared-kernel` + `arknet-persistence-support`, aufgeloest ueber
  `mvn dependency:build-classpath` je Modul (nach `mvn -o -DskipTests install` im Root, damit
  alle Reactor-Artefakte im lokalen `~/.m2` liegen).
- CPU-Last: 16 `yes > /dev/null &`-Prozesse (ein Prozess pro Kern) -- keine
  `stress`/`stress-ng` verfuegbar, kein
  passwortloses `sudo` zur Installation. `uptime` zeigte waehrend der Laeufe eine Load-Average
  zwischen 16 und ~25 (durch zusaetzliche IDE-/JDT-MCP-Java-Prozesse aus parallelen Sessions
  auf derselben Maschine -- die Last war also nicht rein synthetisch, sondern zusaetzlich
  durch echte Nebenlast verschaerft).

Zwei Lastprofile gemessen:
1. **In-Prozess-Wiederholung**: eine JVM startet einmal, fuehrt die Testklasse N-mal
   hintereinander aus (JUnit-Platform-Launcher, frischer `Launcher` pro Iteration, aber
   dieselbe JVM/GC/Klassenlader-Instanz).
2. **Frischer Prozess je Lauf**: `java -cp ... RepeatRunner <log> 1 <Klasse>` -- neue JVM pro
   Wiederholung (naeher an einem echten `mvn test`-Lauf: kalter Klassenlader, kein JIT-
   Warmup, frischer Heap).

## Ergebnis

Alle Zahlen unten sind aus den Rohlogs im Scratchpad nachgezaehlt (`run1.log`, `run2.log`,
`calib.log`, `smoke.log`, `fresh-run.log`, `fresh-rest.log`), nicht aus dem Gedaechtnis
uebernommen.

| Test | Profil | Laeufe | Fehlschlaege | Fehlerrate |
|---|---|---|---|---|
| BoundedContextServiceRealStoreConcurrencyTest | in-process | 525 | 0 | 0% |
| UseCaseServiceRealStoreConcurrencyTest | in-process | 525 | 0 | 0% |
| RequirementServiceRealStoreConcurrencyTest | in-process | 525 | 0 | 0% |
| TermServiceRealStoreConcurrencyTest | in-process | 525 | 0 | 0% |
| BoundedContextServiceRealStoreConcurrencyTest | frischer Prozess | 25 | 0 | 0% |
| UseCaseServiceRealStoreConcurrencyTest | frischer Prozess | 25 | 0 | 0% |
| RequirementServiceRealStoreConcurrencyTest | frischer Prozess | 25 | 0 | 0% |
| TermServiceRealStoreConcurrencyTest | frischer Prozess | 25 | 0 | 0% |

Die 525 in-process-Laeufe je Klasse setzen sich aus vier Teillaeufen zusammen (5 Smoke,
20 Kalibrierung, 200, 300). Bei den Laeufen im frischen Prozess brach der erste Durchgang
mittendrin ab -- `req` stand dort bei 17, `ul` war noch gar nicht gestartet; die fehlenden
8 bzw. 25 Laeufe wurden unter demselben Lastprofil nachgeholt (`fresh-rest.log`), erst damit
gilt die Zeile fuer alle vier Klassen.

Gesamt: 2200 Testklassen-Laeufe (2100 in-process + 100 frischer Prozess), 0 Fehlschlaege,
unter durchgaengiger CPU-Last (16 Kerne saturiert, Load-Average 16-25).

**Kein Fehlschlag eingefangen.** Es gibt daher keinen vollstaendigen Fehlschlag-Beleg
(Stacktrace/Seed) in diesem Protokoll -- das Harness war darauf vorbereitet (siehe
`RepeatRunner.java` im Scratchpad: bei jedem Fehlschlag wird Testname, Iteration, Message
und voller Stacktrace protokolliert), es gab nur nichts zu protokollieren.

## Aussagekraft

0 Fehlschlaege in 2600 Laeufen (2200 `IN_MEMORY` + 400 `PERSISTENT`) bedeutet NICHT "der Bug
existiert nicht". Es bedeutet: die beobachtete Fehlerrate liegt (mit ueblicher statistischer
Vorsicht, Regel des Dreiers) bei einer oberen Schranke von grob 3/2600 ~= 0.12% pro Lauf
(95%-Konfidenz, Annahme unabhaengiger Wiederholungen) -- SOFERN das zugrunde liegende Phaenomen durch diese Art von Last und dieses
Test-Interleaving ueberhaupt angeregt wird. Das ist eine wichtige Einschraenkung, keine
Formalitaet:

- Die vier Tests erzwingen das Interleaving DETERMINISTISCH ueber Barriere/Latch (siehe
  oben). Reine CPU-Last aendert daran nichts an der Reihenfolge, nur am Timing bis dahin.
  Wenn der reale Vorfall (zwei Racer, gleicher Code, real bei Fred beobachtet) tatsaechlich
  hierueber lief, sollte er durch DIESE Tests bei genuegend Wiederholungen sichtbar werden,
  unabhaengig von Last. Dass er es nach 2600 Laeufen nicht war, ist ein Hinweis, aber kein
  Beweis, dass die vier Tests das reale Phaenomen ueberhaupt treffen.
- Alternative Erklaerung: der reale Vorfall trat ausserhalb dieser deterministischen
  Barriere-Konstruktion auf -- z.B. bei mehr als zwei gleichzeitigen Schreibern (die
  `CodeAssignment`-Retry-Schleife hat `DEFAULT_MAX_ATTEMPTS = 20`; bei genuegend gleichzeitigen
  Schreibern des gleichen Typs koennten mehr als 20 aufeinanderfolgende Kollisionen
  auftreten, was den Retry aufgeben liesse -- das waere aber ein LAUTER Fehlschlag
  (die letzte Kollisions-Exception wird geworfen), kein STILLER Code-Doppelgaenger. Der
  beschriebene Vorfall (beide Racer bekommen denselben Code, KEIN Fehlschlag) passt eher zu
  einem Fall, in dem der Store den Konflikt beim Commit schlicht nicht als Konflikt erkannt
  hat.
- Die synthetische Last (16x `yes`) saettigt CPU-Zeit, aendert aber nichts an Speicherdruck
  oder GC-Pausen-Charakteristik ueber die reine CPU-Kontention hinaus. Ein GC-Pause-getriggertes
  Fenster (z.B. ein Full-GC genau zwischen dem `contains`-Check und dem tatsaechlichen Commit
  einer der beiden Transaktionen) wurde damit nicht gezielt provoziert. Festplatten-I/O
  entfaellt im Default-Profil ganz, weil der Store dort `IN_MEMORY` laeuft -- der eigene
  `PERSISTENT`-Lauf weiter unten schliesst diese Luecke.
- Nur EIN Test pro Klasse hat ueberhaupt echte Threads (`concurrentAddCallsUnder...`); der
  zweite Test in `BoundedContextServiceRealStoreConcurrencyTest`
  (`linkTermRetriesAndKeepsBothEdgesWhenAConcurrentWriterAdvancedTheHead`) laeuft
  einzelthreadig ueber einen `beforeTransaction`-Hook und kann per Konstruktion nicht
  scheduling-bedingt flaken.

## Der Store, den die Tests pruefen, ist nicht der Store, der die Daten haelt

Der wichtigste Befund dieser Runde stammt nicht aus den Wiederholungen, sondern aus dem
Testaufbau: **alle vier Tests -- und der gesamte uebrige Testbestand, 43 Vorkommen -- bauen
ihren Store mit `DatasetStoreConfig.Persistence.IN_MEMORY`.** Das Enum kennt daneben
`PERSISTENT`; kein einziger Test benutzt es. Produktiv laeuft der Daemon auf dem NativeStore.

Das trifft den Kern dieses Issues, weil `rdf4j-sail-memory` und `rdf4j-sail-nativerdf` zwei
getrennte Sail-Implementierungen sind -- die Isolation und damit die Konflikterkennung am
Commit haengt an der jeweiligen Sail, nicht an einer geteilten Schicht darueber. Welche
Unterschiede genau bestehen, ist hier NICHT nachgeschlagen worden; belegt ist nur, dass es
zwei verschiedene Codepfade sind. Die Invariante aus #144 ("zwei parallele Schreiber bekommen nie denselben
Business-Code") ist damit bisher **ausschliesslich gegen den MemoryStore** bewiesen worden,
also gegen einen Store, der im Betrieb keine Nutzerdaten haelt. Der Schaden, den dieses Issue
fuerchtet -- zwei gleiche Codes liegen im Store --, entstuende im NativeStore.

Fred hat die Suche im Issue-Kommentar vom 2026-07-27 genau dorthin gelenkt: seit #173/PR #28
ist die Uebersetzung (`WriteFunnel.DEFAULT_WRITE_CONFLICT`) durch einen eigenen Unit-Test
abgedeckt, "die Suche gehoert also in den Store-Pfad (RDF4J-`SERIALIZABLE`), nicht in die
Uebersetzung". Der Wechsel des Persistenz-Modus ist der billigste verfuegbare Hebel in genau
diesen Pfad: eine Zeile je Test.

### Messung gegen PERSISTENT

Dieselben vier Tests, dieselbe Last, `Persistence.IN_MEMORY` -> `Persistence.PERSISTENT`
(vier Zeilen, siehe `persistent-run.log`):

| Test | Profil | Laeufe | Fehlschlaege | Fehlerrate |
|---|---|---|---|---|
| BoundedContextServiceRealStoreConcurrencyTest | PERSISTENT, in-process | 100 | 0 | 0% |
| UseCaseServiceRealStoreConcurrencyTest | PERSISTENT, in-process | 100 | 0 | 0% |
| RequirementServiceRealStoreConcurrencyTest | PERSISTENT, in-process | 100 | 0 | 0% |
| TermServiceRealStoreConcurrencyTest | PERSISTENT, in-process | 100 | 0 | 0% |

**Auch hier kein Fehlschlag.** Das raeumt die naheliegendste Fassung von Hypothese 1 ab: es
ist nicht so, dass der MemoryStore den Konflikt erkennt und der NativeStore ihn verschlaeft --
unter dieser Interleaving-Konstruktion erkennen ihn beide, 400 Laeufe lang.

Zwei Dinge, die dieser Lauf trotzdem wert war:

- Die Tests laufen unter `PERSISTENT` **ohne Anpassung durch** (eine Zeile je Klasse, 100
  Laeufe in rund 50 Sekunden). Ein dauerhafter Testlauf gegen den Store-Typ, der die Daten
  wirklich haelt, ist also nicht teuer -- die Abdeckungsluecke ist eine Entscheidung, keine
  technische Huerde. Ob sie geschlossen wird (parametrisiert ueber beide Modi, oder Wechsel
  auf `PERSISTENT`), gehoert entschieden, nicht nebenbei mitgeaendert -- deshalb steht hier
  nur der Befund, und die vier Zeilen sind zurueckgesetzt.
- Der Testaufbau raeumt sein `Files.createTempDirectory` nicht ab (`@AfterEach` ruft nur
  `shutDownAll()`). Unter `IN_MEMORY` bleiben leere Verzeichnisse liegen und es faellt nicht
  auf; unter `PERSISTENT` waren es nach 400 Laeufen rund 21 MB in `/tmp`. Kosmetisch, solange
  der Default `IN_MEMORY` ist -- aber es ist der zweite Grund, den Modus nicht stillschweigend
  umzustellen.

## Hypothesenliste (NICHT umgesetzt, nur aufgeschrieben)

1. **Store erkennt den Konflikt in einem Grenzfall nicht.** RDF4J's `SailRepository`
   unter SERIALIZABLE-Isolation (`kogn-io/rdf-core#18`) koennte eine seltene Kombination aus
   Query-Form (die zwei `contains`-Checks in `WriteFunnel.create`, dann der eigentliche
   `body`-Write, dann die `recordRevision`-Schreibungen im selben Transaktions-Snapshot)
   geben, bei der der Snapshot-Vergleich beim Commit nicht triggert -- z.B. wenn die
   Provenance-Graph-Schreibungen (`recordRevision`) NACH dem eigentlichen Code-Write
   passieren und der Konflikt-Check des Stores nur auf bestimmten Graph-Mustern reagiert.
   Pruefbar nur mit Einblick in `io.kogn.rdf`s Transactor-Implementierung (nicht Teil dieses
   Repos).
2. ~~**`isWriteConflict`-Predicate zu eng.**~~ **AUSGESCHLOSSEN, nicht weiterverfolgen.**
   Diese Hypothese lag nahe, ist aber seit #173/PR #28 erledigt: `WriteFunnel.
   DEFAULT_WRITE_CONFLICT` hat mit `WriteFunnelTest#defaultWriteConflictRecognisesOnlyThe
   StoresConcurrencyConflict` einen direkten Unit-Test, angelegt genau deshalb, weil die vier
   Real-Store-Tests wegen dieses Issues als alleiniger Beweis unzuverlaessig sind. Fred haelt
   das im Issue-Kommentar vom 2026-07-27 fest und zieht die Konsequenz: die Suche gehoert in
   den Store-Pfad, nicht in die Uebersetzung. Hier steht sie nur noch, damit die naechste
   Sichtung nicht wieder bei ihr anfaengt.
3. **Mehr-als-zwei-Schreiber-Erschoepfung des Retries.** `CodeAssignment.DEFAULT_MAX_ATTEMPTS
   = 20` -- bei realer Nutzung (mehrere parallele MCP-Sessions/Worktrees gegen denselben
   Store, ADR-001) koennten mehr als zwei gleichzeitige Schreiber sein; dieses Szenario testen
   die vier Tests nicht (sie forcieren exakt zwei). Waere aber ebenfalls ein LAUTER Fehlschlag,
   kein stiller Doppelcode -- passt schlechter zum berichteten Symptom.
4. **Der reale Vorfall lag nicht im WriteFunnel-Pfad, sondern in einer der Sonderrouten.**
   ADR-013/ADR-014 nennen explizite Sonderpfade (`compareAndUpdate` fuer `req_update`,
   `term_update`, `bc_link_term`), die ein anderes Signal (`headMismatch`) benutzen. Wenn der
   beobachtete Vorfall tatsaechlich ein `add`/Create-Race war (wofuer die Beschreibung
   "UC1 bzw. BC-1" spricht -- beides Create-Codes, keine Update-Codes), ist dieser Pfad
   unwahrscheinlich, aber nicht ausgeschlossen ohne den urspruenglichen Vorfall-Kontext.
5. **Zeitliches Fenster ausserhalb der Barriere-Konstruktion.** Der reale Vorfall koennte auf
   einem Interleaving beruhen, das die deterministische Barriere-Konstruktion dieser Tests gar
   nicht abbildet -- z.B. drei oder mehr Racer, oder ein Racer, dessen zweiter `contains`-Check
   (Code-Uniqueness) VOR dem ersten `contains`-Check (Identity) eines anderen Racers laeuft
   (die Tests pinnen exakt "beide Guards bereits bestanden", nicht jede moegliche Reihenfolge
   der zwei Checks zwischen zwei Racern).

## Empfehlung fuer den naechsten Schritt

Diese vier Tests pruefen (per Bauart, nicht nur per Messung) eine deterministische
Store-Konflikterkennung, keine scheduling-abhaengige Racebedingung -- Barriere und Latch
fixieren die Reihenfolge, CPU-Last verschiebt nur, wann sie erreicht wird. Weiteres
"mehr Last drauf"-Fuzzing dieser vier Tests ist damit der falsche Hebel, und diese Runde
sollte die letzte ihrer Art gewesen sein: 2600 Laeufe sind Beleg genug, dass hier nichts
mehr herausfaellt.

Die naechsten Schritte, nach absteigendem erwarteten Ertrag:

1. **Den urspruenglichen Vorfall rekonstruieren, statt ihn nachzustellen.** Beide Sichtungen
   fielen in einem vollen Reaktor-Build an (`mvn verify` bzw. `mvn -T1C test`), nicht in einem
   isolierten Lauf. Was dort zusaetzlich passiert und hier fehlt: `-T1C` laesst mehrere Module
   gleichzeitig testen, also mehrere Surefire-JVMs auf denselben Kernen, mit ganz anderem
   Speicher- und GC-Druck als 16 `yes`-Prozesse. Der naechste Versuch sollte deshalb den
   **vollen parallelen Build** wiederholen (`mvn -o -T1C test` in einer Schleife), nicht die
   Einzelklasse -- das ist das einzige Profil, unter dem der Fehler je aufgetreten ist.
2. **Die Abdeckungsluecke `IN_MEMORY` vs. `PERSISTENT` schliessen** (siehe oben). Sie hat den
   Flake nicht erklaert, aber sie bleibt eine Luecke: die #144-Invariante ist gegen den
   produktiven Store bis heute nur durch die 400 Laeufe dieses Protokolls belegt, nicht durch
   den Testbestand.
3. **GC-Druck gezielt provozieren**, falls (1) nichts bringt: kleines `-Xmx` plus
   Allokationslast parallel zum Racer-Thread, um ein Fenster zwischen `contains`-Check und
   Commit zu erzwingen -- die einzige verbliebene Erklaerung, die zu "unter Last, sonst nie"
   passt.

Was **nicht** mehr Gegenstand sein sollte: die Uebersetzung des Store-Signals (durch #173
testabgedeckt, siehe Hypothese 2) und weitere Wiederholungen der vier Einzelklassen unter
kuenstlicher CPU-Last.
