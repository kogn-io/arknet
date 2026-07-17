# ADR-007: Geteiltes SHACL-Write-Gate als eigenes technisches Modul, nicht im Shared Kernel

- Status: Accepted (2026-07-15)
- Verwandt: ADR-006 (generischer Store-Lesepfad -- **gegenteilige Antwort auf dieselbe Frage**),
  ADR-001 (austauschbarer Store), ADR-005 (Store-first)
- Issue: #52

## Kontext

Jede der drei BCs (requirements, ubiquitous-language, use-cases) validiert vor dem Commit ihren
Kandidaten-Graph gegen ihre SHACL-Shapes und lehnt ihn bei Verletzung ab (validate-before-commit).
Der Code dafuer war in allen drei `*-adapter-kogniordf`-Modulen **wortgleich kopiert**; die
uc-Kopie trug die Schuldnotiz "Mirror, not reuse ... deliberate later step (open point)" bereits im
Javadoc. Mit der dritten BC ist die Rule of Three erfuellt.

Zu entscheiden war: (a) Wo lebt das geteilte Gate -- `arknet-shared-kernel`, eigenes Modul, oder
weiter dupliziert? (b) Wie bleibt die Technologie-Neutralitaet erhalten, wenn alle drei Aufrufer
RDF4J ziehen? (c) Wie werden die Kontextunterschiede ausgedrueckt, ohne die BCs aneinander zu
binden?

**Die Frage aus (a) ist strukturell dieselbe wie in ADR-006 -- dort lautete die Antwort "kein
eigenes Modul".** Warum sie hier anders ausfaellt, ist der Kern dieser ADR (siehe Abgrenzung).

## Entscheidung

1. **Eigenes Modul `arknet-persistence-support`, nicht der Shared Kernel.** Das Gate
   (`de.hauschel.arknet.persistence.ShaclWriteGate` + `WriteConstraintViolationException`) zieht
   die kognio-rdf-Ports `rdf-terms` und `rdf-shacl`. Der tragende Grund ist **nicht** primaer
   "Technik statt Domaene", sondern der **Konsumentenkreis**: `arknet-shared-kernel` wird von den
   drei `*-core` konsumiert und muss deshalb dependency-frei bleiben; das Gate wird ausschliesslich
   von den drei `*-adapter-kogniordf` konsumiert und darf deshalb Ports tragen. Ein Merge der
   beiden Module wuerde `io.kogn.rdf:*` in den Classpath der Cores ziehen und deren Reinheit
   zerstoeren. Die Trennung ist lasttragend, keine Geschmacksfrage.

2. **Das Gate bleibt RDF4J-frei.** Es kennt nur `io.kogn.rdf.shacl` und `io.kogn.rdf.terms`; die
   konkrete `ShaclValidation` und die geladenen Shapes-/Axiom-Graphen werden von der jeweiligen
   Repository-Factory hereingereicht. Die Factory bleibt der einzige RDF4J-bewusste Kollaborateur
   -- pro Out-Adapter genau eine Datei, die `org.eclipse.rdf4j`/`io.kogn.rdf.rdf4j` nennt. Das
   Modul traegt damit **keine** RDF4J-Abhaengigkeit, obwohl jeder seiner Aufrufer eine hat.

3. **Kontextunterschiede sind Konstruktor-Zustand, kein Code *im Gate*.** Jede BC baut ihr Gate in
   ihrer eigenen Factory (`buildGate()`) aus ihren eigenen Ressourcen; keine BC sieht die Parameter
   einer anderen, das Gate ist pro Aufruf zustandsfrei. Die realen Unterschiede:
   - **req**: volle `requirements-shapes.ttl`, Axiome `arknet-requirements.ttl`,
     `new ValidationOptions(true)` -- Reasoning, damit Shapes auf Oberklassen feuern (`subClassOf`).
   - **ul**: `ul-shapes.ttl`, **leerer** Axiom-Graph, `ValidationOptions.defaults()` -- kein
     Reasoning noetig, die Instanzen tragen den getargeteten Typ bereits.
   - **uc**: Axiome und Options **identisch zu req**. Der echte uc-Unterschied ist ein anderer:
     `loadUseCaseShapes()` **filtert** die Shapes (entfernt jedes `sh:targetClass`, das nicht
     `arkreq:UseCase`/`arkreq:Step` ist), damit ein Use-Case-Write nicht gegen `RequirementShape`
     validiert wird. **Diese Filterung ist Code** -- in der uc-Factory, nicht im Gate.

## Abgrenzung zu ADR-006: warum hier "eigenes Modul" und dort nicht

ADR-006 entschied fuer den generischen Store-**Lese**pfad ausdruecklich *kein* eigenes Modul und
verwies ihn ins Composition Root. Beide Male geht es um einen BC-uebergreifenden, technischen
Baustein -- die Antwort faellt aus drei Gruenden verschieden aus:

| | Store-Report (ADR-006) | Write-Gate (ADR-007) |
|---|---|---|
| Aufrufer | **null** -- nur das Composition Root selbst | **drei** Out-Adapter, in drei Modulen |
| Duplikation heute | keine (gibt es nur einmal) | **dreimal wortgleich kopiert**, mit Schuldnotiz |
| Wer braucht es? | ein Konsument, der ohnehin alles sieht | Module, die einander **nicht** sehen duerfen |

Die Leitregel, die beide ADRs gemeinsam tragen: **ein geteilter technischer Baustein bekommt erst
dann ein eigenes Modul, wenn er von mehreren Modulen gebraucht wird, die einander nicht sehen
duerfen.** Ist der einzige Konsument das Composition Root, gehoert er dorthin (ADR-006). Sind es
mehrere Adapter, geht es ohne Modul nur per Copy-Paste (ADR-007). Ein eigenes Modul ist in beiden
Faellen die Antwort auf einen belegten Bedarf, nie auf eine Vermutung.

## Konsequenzen

**Positiv:**

- ~90 Zeilen Dreifach-Kopie beseitigt; die Schuldnotiz der uc-Kopie ist eingeloest.
- Die Gate-Semantik ist an genau einer Stelle testbar (`ShaclWriteGateTest`) statt dreimal
  approximiert.
- Ein vierter BC erbt das Gate durch eine POM-Zeile.

**Negativ / bewusst:**

- **Der DRY-Gewinn ist partiell.** `loadGraph(String)` (Rio.parse + `RDF4JGraph`) steht weiterhin
  dreimal wortgleich in den Factories. Das ist die Kehrseite von Entscheidung 2: das Ausziehen
  dieses Glue-Codes wuerde RDF4J ins Modul holen und genau die Eigenschaft brechen, die es
  auszeichnet. Ein zweites, RDF4J-bewusstes Modul (`arknet-persistence-support-rdf4j`) waere der
  Ausweg -- fuer ~30 Zeilen Glue derzeit nicht gerechtfertigt. Bewusst offen gelassen.
- **Der Modulname ist unscharf.** `-support` sagt, wofuer das Modul da ist, nicht was drin ist, und
  ist ein bekannter Grabbelkisten-Attraktor: fuer aktuell eine Klasse plus eine Exception eine
  Nummer zu gross. Gegenmittel ist bis auf Weiteres das `<description>` im POM, das den Inhalt
  festnagelt. Wird das Modul zur Halde, ist der Name das erste, was faellt (`arknet-shacl-write-gate`).
- **Die RDF4J-Freiheit haengt am Modul-POM, nicht am Modulschnitt.** Sie gilt, solange niemand eine
  `rdf4j-*`-Dependency aufnimmt -- eine einzige Zeile kippt sie. Dasselbe gilt fuer "nur die Factory
  nennt RDF4J-Typen", eine modulinterne Regel, die Maven prinzipiell nicht sehen kann. Beides sind
  stille Fehler: kein Compiler-Fehler, kein roter Test, nur Reviewer-Aufmerksamkeit. Diese
  Entscheidung erzwingt daher eine Kontrolle ausserhalb des Modulschnitts -- ArchUnit-Regeln in
  `arknet-architecture-tests`, die den Zugriff auf RDF4J-Typen aus diesem Modul verbieten. Deren
  Reichweite endet am Bytecode: eine ungenutzte `rdf4j-*`-Dependency im POM bleibt unsichtbar. Sie
  ist dann Ballast, aber kein Bruch -- die Eigenschaft kippt erst bei Nutzung, und die faellt auf.
- **ADR-006 Punkt 3 bleibt unberuehrt.** Die Fabrikmethode `persistentLifecycle(Path)` haengt
  weiter physisch am requirements-Adapter, obwohl der Lifecycle BC-neutral ist. Das jetzt
  existierende Infra-Modul entkraeftet zwar das damalige Argument ("kein neues Infra-Modul fuer eine
  Methode"), loest das Problem aber nicht: `persistentLifecycle` konstruiert `DatasetLifecycleRdf4j`
  und braucht damit RDF4J -- es kann aus genau demselben Grund nicht nach
  `arknet-persistence-support` wie `loadGraph`.

## Alternativen

- **Ins `arknet-shared-kernel`.** Verworfen: zoege die kognio-rdf-Ports in den Classpath der drei
  `*-core` und zerstoerte deren Dependency-Freiheit. Ausserdem ist das Gate kein geteiltes
  Domaenen-Vokabular, sondern Technik -- ein Shared Kernel, in den Infrastruktur sickert, hoert auf,
  einer zu sein.
- **Duplikation belassen.** Verworfen: n=3, die Klasse ist stabil, und die Kopie war bereits als
  Schuld markiert. Duplikation waere hier nicht die konservative, sondern nur die traege Wahl.
- **Ins Composition Root (`arknet-mcp`), analog ADR-006.** Verworfen: die Out-Adapter duerfen nicht
  gegen das Composition Root kompilieren -- das kehrte die Dependency-Richtung des Hexagons um.
- **Gate als Interface im Core, Implementierung je Adapter.** Verworfen: validate-before-commit ist
  eine Persistenz-Invariante, keine Domaenenregel; im Core waere es ein Port ohne fachlichen Sinn,
  und die Implementierungen blieben dupliziert.

## Nachtrag 2026-07-16 (#63)

Der `validationGraph`-Workaround der Out-Adapter (req, uc) -- eine volle Kopie des Kandidaten-
Graphen plus synthetische Typ-Tripel fuer referenzierte Nachbar-Knoten, nur um sie durch das Gate
zu schleusen -- wurde in einen Gate-Overload ueberfuehrt:
`ShaclWriteGate.enforce(ReadableGraph candidate, ReadableGraph assertedContext)`. Das Gate merged
`candidate` + `assertedContext` + `axioms` fuer die Validierung; persistiert wird weiterhin nur
`candidate`, wie zuvor.

Das bleibt mit Entscheidung 3 ("Kontextunterschiede sind Konstruktor-Zustand, kein Code im Gate")
vereinbar: `assertedContext` ist ein **Parameter**, kein gate-internes Kontextwissen -- das Gate
weiss weiterhin nicht, *welche* Nachbar-BC oder welcher Typ dahintersteckt. Welche Typ-Tripel
synthetisiert werden (`skos:Concept` fuer Terms in req, `arkproc:Actor`/`arkreq:Requirement` fuer
Actors/Requirements in uc), entscheidet weiterhin jede Adapter-Factory bzw. jedes `save()` selbst.
Die bestehende Ein-Parameter-Methode `enforce(candidate)` bleibt erhalten und delegiert an
`enforce(candidate, <leerer Graph>)`; der ul-Adapter, der keinen Nachbar-Kontext braucht, ruft sie
unveraendert auf.

Bei derselben Gelegenheit (#63) wurden zwei bislang je BC duplizierte Bausteine nach
`arknet-persistence-support` gezogen, weil sie inzwischen n=2 (bzw. n=3 fuer das SPARQL-Escaping)
erreicht hatten:

- **`SparqlTerms`** (`escape`/`isValidIriReference`/`iriRef`) buendelt das SPARQL-String-Escaping
  der Out-Adapter. Der mitgezogene Fix: `escape` behandelt jetzt auch LF/CR/TAB (`\n`/`\r`/`\t`),
  nicht nur Backslash und Anfuehrungszeichen -- ein Literal mit eingebettetem Zeilenumbruch haette
  sonst eine SPARQL-Query zerrissen.
- **`UnresolvedReferenceException`** war wortgleich in req- und uc-Adapter dupliziert (Rule of
  Three erfuellt); jetzt eine Klasse mit oeffentlichem Konstruktor (beide Adapter werfen sie aus
  fremdem Package).

Beide Klassen sind technologieneutral (kein RDF4J-Bezug) und aendern nichts an der
RDF4J-Freiheit des Moduls.

## Nachtrag 2026-07-17 (#65): Reichweite des Gates

Der #65-Fix (PR #76) haengt im req-Out-Adapter Tripel an, die nicht durch `gate.enforce` gelaufen
sind -- `usesTerm`-Kanten, die der Lese-Join nicht binden kann und die das
replace-by-identity-`update` sonst still loeschen wuerde (Mechanik: Javadoc `readUsesTerms`).
Erster Bypass dieser Art im Schreibpfad.

**Entscheidung:** Das Gate ist ein Tor fuer **neue** Behauptungen der Anwendung, keine
fortlaufende Integritaetsgarantie ueber den Gesamtgraphen -- store-first (ADR-005) laeuft ohnehin
prinzipiell daran vorbei. Bestand bewahren darf am Gate vorbei; Tripel **erzeugen** nicht. Wer den
Bypass fuer eine andere Kante wiederverwendet, muss zeigen, dass er ebenfalls nur bewahrt.

Die Kante durch das Gate zu schleusen war keine Option: ihr Ziel traegt per Konstruktion keinen
Identifier, bekommt also keinen synthetischen Typ in den `assertedContext`, und `sh:class
skos:Concept` der `usesTerm`-Shape kippte die Validierung -- jedes kuenftige `update` eines
betroffenen Requirements waere blockiert. Am Gate selbst aendert der Bypass nichts, die
Entscheidung liegt im Adapter (vgl. Entscheidung 3).

**Praezisierung 2026-07-17 (#77, PR #79):** Die Entscheidung oben gilt unveraendert, ihr
Anwendungsbereich ist aber kleiner geworden. Das definierende Kriterium der bewahrten Kanten war
"Ziel traegt keinen `dcterms:identifier`"; seit `TermRef` die Subject-`ResourceId` traegt und der
Lese-Rueckjoin ersatzlos entfaellt, ist es **"Ziel ist keine IRI"** -- Blank Nodes, die eine
`ResourceId` nicht darstellen kann. Der Bypass bleibt also bestehen, betrifft aber nur noch diesen
Restfall (Mechanik jeweils aktuell im Javadoc `readUsesTerms`, nicht hier). Die daraus
resultierende Grenze ist noch praeziser zu ziehen als oben formuliert: nicht nur "Bestand bewahren
darf am Gate vorbei", sondern auch -- wer dem `assertedContext` einen synthetischen Typ beilegt,
den **niemand** geprueft hat, macht das Gate fuer genau diese Bedingung blind. Fuer die von
`readUsesTerms` gelesenen Kanten trifft das zu (der Read stellt keine Typ-Bedingung); erreichbar
ist der Fall nur store-first, nicht ueber die Tools. Kein eigenes Issue -- bewusste Entscheidung,
festgehalten im Klassen-Javadoc von `KognioRdfRequirementRepository`.
