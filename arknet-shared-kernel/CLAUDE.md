# arknet-shared-kernel

DDD Shared Kernel -- technologieneutrale, von mehreren BCs geteilte Domain-Bausteine: `de.hauschel.arknet.kernel.ProjectId` sowie die opake Ressourcen-Identitaet `ResourceId` (sealed, `of(String)` wrapt eine bestehende `https://`-IRI und validiert im Konstruktor vollstaendig: `https://`-Prefix, keine Whitespace UND keine der IRIREF-verbotenen Zeichen `<>"{}|^\`\`+Steuerzeichen -- dieselbe Zeichenregel wie `SparqlTerms.isValidIriReference` in `arknet-persistence-support`, notwendig dupliziert, da der Kernel nicht am Support haengen darf), `ResourceIdFactory`-Port + `UuidResourceIdFactory` (mintet flach unter `https://w3id.org/arknet/id/<uuid>`, kein BC-/Typ-Segment -- der Typ lebt in `rdf:type`). Zusaetzlich `DisplayLocale` (Record `requested`/`systemDefault: Locale`, statische `DEFAULT`-Instanz, Methode `select(Collection<LocalizedLiteral>)`) und `LocalizedLiteral` (Record `value`/`languageTag`, technologieneutrale Projektion eines sprachgetaggten RDF-Literals) -- die Anzeige-Sprachauswahl fuer mehrsprachige `skos:prefLabel`-Literale, ueber eine vierstufige Fallback-Kette: angefordertes Locale -> System-Default -> ungetaggtes Literal -> deterministischer Fallback ueber eine stabile Sortierung (nie ein hartes `FILTER`, das eine Ressource ohne Label in der angeforderten Sprache stumm verschluckt). `DisplayLocale`/`LocalizedLiteral` liegen im Kernel, weil sie ein Wert pro Prozess sind, einmal im Composition Root konfiguriert und in die Bounded Contexts injiziert. Zusaetzlich der pure Helfer `CodeAssignment` (`createRetryingOnCodeCollision`): die von allen vier BCs geteilte "naechsten Business-Code lesen-berechnen, `create()`, bei Kollision neu berechnen"-Schleife, generisch ueber Ressourcentyp und die jeweilige `Duplicate<Typ>CodeException`. Liegt im Kernel und **nicht** in `arknet-persistence-support`, obwohl dort schon geteilte Out-Adapter-Technik wohnt: persistence-support traegt `io.kogn.rdf`-Compile-Deps, und die `*-core`-Services, die den Helfer rufen, muessen RDF-frei bleiben (ArchUnit Regel 3). Der Kernel ist das eine technologieneutrale Modul, an dem jeder `*-core` ohnehin haengt -- so bleibt der Helfer reines JDK und zieht kein RDF auf einen puren Core-Classpath. Bewusst winzig.

`ProjectId` traegt die Invariante `RESERVED_SYSTEM_DATASET` (`urn:arknet:system`, das Dataset der
Projekt-Registry) und lehnt genau diesen Wert im Konstruktor ab. Die Konstante liegt hier und nicht
im `arknet-project-core`, obwohl dort der Lebenszyklus des Bezeichneten verwaltet wird: die
Modell-BC-Cores routen auf dieser Identitaet und duerfen nicht an einem Nachbar-BC haengen,
waehrend der Kernel das eine Modul ist, an dem jeder Core ohnehin haengt. Die Form des Werts ist
sonst unbeschraenkt (nur non-blank) -- neue Projekte minten eine UUID, aus der alten Slug-Ableitung
gewachsene Ids wie `arknet` bleiben gueltige opake Werte und werden nie migriert (ADR-016 Punkt 5).

Dazu der Port `ProjectResolver` (`String anchor -> ResolvedProject`, Konstante `ANCHOR_KEY`):
`ProjectId` ist **kein** Prozess-Singleton, sondern wird von jedem `@McpTool`-Adapter pro Aufruf aus
dem Anker aufgeloest, den der Client mitschickt. Die Aufloesung ist ein Registry-Nachschlagen auf
den ganzen, uninterpretierten Wert -- nichts wird abgeleitet, gekuerzt oder geraten. Die
Composition-Root-Implementierung `RegisteredAnchorProjectResolver` in `arknet-mcp` adaptiert dafuer
den `ResolveProject`-In-Port des `arknet-project`-BC; die Modell-BCs sehen nur diesen neutralen
Port und haengen nie an jenem BC. Fehlender oder unbekannter Anker ist ein Fehler
(`UnresolvedProjectAnchorException`, ebenfalls im Kernel, damit die Uebersetzung der
BC-eigenen `UnknownAnchorException` an der Portgrenze stattfindet) -- es gibt keinen Default und
keinen Rueckfall auf ein Server-Arbeitsverzeichnis, und bewusst auch keine
`Optional`-Variante der Methode, die an der Aufrufstelle wieder zum Erfinden eines Fallbacks
einladen wuerde. Shared-Kernel-Grund derselbe wie bei `ProjectId` selbst: mehrere BCs
(requirements, ubiquitous-language, ...) adressieren dasselbe Projekt und teilen sich einen Weg,
es aufzuloesen, statt jeder seinen eigenen zu erfinden.

Das Ergebnis ist `ResolvedProject` (Record `id: ProjectId`/`defaultLanguage: String`, Letzteres
nullable): der Port liefert seit der Mehrsprachigkeit von Term/Project (arknet-ubiquitous-language,
arknet-project) nicht mehr nur die `ProjectId`, sondern buendelt das konfigurierte
Standard-Anzeige-Language-Tag des aufgeloesten Projekts gleich mit -- derselbe Registry-Read, den
jeder Tool-Aufruf ohnehin fuer das Routing macht, beantwortet die Sprachfrage "for free" mit,
statt dass ein Bounded Context, der die Standardsprache braucht (heute nur
ubiquitous-language, fuer `term_get`s Anzeige-Fallback), den Project-BC dafuer ein zweites Mal ueber
einen eigenen Borrowed In-Port ansprechen muesste. Ein Aufrufer, der nur die `ProjectId` braucht, liest
`resolve(anchor).id()`. `defaultLanguage` bedient seit Issue #258 zwei Rollen: lesend waehlt es,
welche Sprachvariante ein Lesepfad ohne explizite Anfrage bevorzugt zeigt (unveraendert); schreibend
ist es fuer requirements/ubiquitous-language/use-cases der Fallback, auf den `LanguageTag#resolveWriteLanguage`
ein weggelassenes `language`-Argument aufloest -- ein Schreib-Tool ohne eigenes `language` schreibt
also nicht mehr ungetaggt, sondern unter `defaultLanguage`; hat das Projekt keins konfiguriert UND
der Aufrufer auch kein `language` mitgegeben, lehnt der Aufruf mit `MissingDefaultLanguageException`
ab, statt still ungetaggt zu schreiben. `arknet-project`s eigener Beschreibungs-Schreibpfad
(`project_add`/`project_update`) bleibt davon unberuehrt: er kanonisiert ein mitgegebenes `language`
weiterhin nur mit `LanguageTag#canonicalize` und schreibt ohne eins ungetaggt, da ein Projekt kein
Konzept einer eigenen Default-Sprache-fuer-sich-selbst hat (es *ist* die Quelle von `defaultLanguage`
fuer die anderen BCs).

Der pure Helfer `LanguageTag` (`canonicalize(String)`) kanonisiert jeden von aussen kommenden
`language`-Wert (Term/Project) auf seine normalisierte BCP-47-Form (`"DE"` -> `"de"`), bevor ein
Out-Adapter ihn schreibt oder einen sprachscoped Delete-Filter damit baut -- der Grund liegt hier,
nicht in `Locale.forLanguageTag`: dessen eigenes Javadoc nennt es bewusst nachsichtig, es wirft nie
und laesst einen nicht parsbaren Rest fallen, sodass ein Tippfehler wie `"de_DE"` (Java-`Locale`s
eigene `toString()`-Konvention nutzt Unterstrich, BCP-47 Bindestrich) stillschweigend bis `"und"`
(unbestimmt) degradiert. `LanguageTag` nutzt stattdessen `Locale.Builder#setLanguageTag`, das exakt
auf diesem Fall wirft, und uebersetzt das in die eigene `InvalidLanguageTagException`, statt einen
`java.util`-Typ ueber die Port-Grenze lecken zu lassen. Liegt im Kernel, weil sowohl
arknet-ubiquitous-language als auch arknet-project denselben Helfer brauchen und ein nicht
kanonisierter Tag sonst asymmetrisch zwischen Schreiben und dem zugehoerigen sprachscoped Delete
divergieren kann (zwei verschieden gecaste Literale fuer dieselbe Sprache statt einer Korrektur).

Dieselbe Klasse traegt seit Issue #258 zusaetzlich `resolveWriteLanguage(String explicit, String
projectDefaultLanguage)`: die eine Stelle, an der jeder Schreibpfad von requirements,
ubiquitous-language und use-cases ermittelt, unter welchem Tag ein Feld tatsaechlich geschrieben
wird -- `explicit` (kanonisiert) gewinnt immer, sonst `projectDefaultLanguage` (kanonisiert), sonst
wirft die Methode `MissingDefaultLanguageException` (ebenfalls im Kernel), statt still ein
ungetaggtes Literal zu schreiben. Loest damit die fruehere Design-Entscheidung ab, nach der ein
weggelassenes `language`-Argument immer ungetaggt blieb (issue #228/PR #230): jenes Verhalten liess
sich mit den vorhandenen Tools weder entfernen noch nachtraeglich taggen und produzierte bei einem
spaeteren Update auf ein bereits sprachgetaggtes Feld eine dauerhafte ungetaggte Dublette. Der zu
dieser Umkehrung gehoerige Sweep bestehender ungetaggter Literale (ein Schreiben unter dem
kanonisierten `defaultLanguage` raeumt ein noch bestehendes ungetaggtes Literal desselben
Praedikats/Subjects mit auf, statt es als vermeintliche andere Sprachvariante zu bewahren) ist kein
gemeinsamer Kernel-Mechanismus, sondern in jedem der drei Out-Adapter in dessen eigenem Stil
nachgebaut (Java-Stream-Filter bei req/uc, SPARQL-`FILTER`-Erweiterung bei ul) -- Details je in
`arknet-requirements/CLAUDE.md`, `arknet-ubiquitous-language/CLAUDE.md`, `arknet-use-cases/CLAUDE.md`.

Neben `CodeAssignment` (der Schreibhaelfte des Code-Zaehlers) liegt seit kogn-io/arknet#360 dessen
Lesehaelfte `CodeCounter` (`runningNumber(codePrefix, code)`, `highestRunningNumber(codePrefix,
codes, codeValue)`).
Sie loest sieben in den `*-core`-Services duplizierte `runningNumber`-Kopien ab, die in **zwei**
verschiedenen Parse-Varianten auseinandergelaufen waren: "die Ziffern nach dem letzten Bindestrich"
fuer `TERM-7` und "die Ziffern nach den fuehrenden Buchstaben" fuer `UC12`.
Der Helfer kommt ohne Trennzeichen-Konvention aus, weil der Aufrufer genau das Praefix-Literal
uebergibt, mit dem er auch praegt (`TERM-`, `UC`, `FR-`, `ACTOR-`) -- Praegen und Zaehlen lesen
damit dieselbe Konstante, und `UC` faellt nicht mehr aus der Reihe.
Der Match ist **am Anfang verankert**: ein Code, der nicht mit dem Praefix beginnt, zaehlt nicht,
und was auf das Praefix folgt, muss reine Ziffern sein (sonst 0 -- store-first-Daten sind
zu ueberleben, nicht zu quittieren; eine gepraegte Nummer beginnt bei 1, kann also nie mit 0
kollidieren).
Genau diese Verankerung ersetzt in `RequirementService`/`ConstraintService` den fruehreren Filter
auf den Domaenentyp (`FR`/`NFR`, `TCON`/`BCON`/`RCON`): die Partition steckt im Code selbst, sodass
der Zaehler nicht mehr davon abhaengt, ob das Typ-Tripel einer Ressource lesbar ist -- der Punkt der
ganzen Aenderung.
Shared-Kernel-Grund derselbe wie bei `CodeAssignment`: jeder `*-core` braucht den Helfer und muss
RDF-frei bleiben (ArchUnit Regel 3); die Out-Adapter duerfen ihn mitbenutzen und tun es dort, wo sie
Codes nach laufender Nummer sortieren (`KognioRdfAdrRepository#CODE_BY_RUNNING_NUMBER`), damit
Sortier- und Zaehl-Parse nicht auseinanderdriften koennen.
