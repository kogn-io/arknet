# arknet-shared-kernel

DDD Shared Kernel -- technologieneutrale, von mehreren BCs geteilte Domain-Bausteine: `de.hauschel.arknet.kernel.ProjectId` sowie die opake Ressourcen-Identitaet `ResourceId` (sealed, `of(String)` wrapt eine bestehende `https://`-IRI und validiert im Konstruktor vollstaendig: `https://`-Prefix, keine Whitespace UND keine der IRIREF-verbotenen Zeichen `<>"{}|^\`\`+Steuerzeichen -- dieselbe Zeichenregel wie `SparqlTerms.isValidIriReference` in `arknet-persistence-support`, notwendig dupliziert, da der Kernel nicht am Support haengen darf), `ResourceIdFactory`-Port + `UuidResourceIdFactory` (mintet flach unter `https://w3id.org/arknet/id/<uuid>`, kein BC-/Typ-Segment -- der Typ lebt in `rdf:type`). Zusaetzlich `DisplayLocale` (Record `requested`/`systemDefault: Locale`, statische `DEFAULT`-Instanz, Methode `select(Collection<LocalizedLiteral>)`) und `LocalizedLiteral` (Record `value`/`languageTag`, technologieneutrale Projektion eines sprachgetaggten RDF-Literals) -- die Anzeige-Sprachauswahl fuer mehrsprachige `skos:prefLabel`-Literale, ueber eine vierstufige Fallback-Kette: angefordertes Locale -> System-Default -> ungetaggtes Literal -> deterministischer Fallback ueber eine stabile Sortierung (nie ein hartes `FILTER`, das eine Ressource ohne Label in der angeforderten Sprache stumm verschluckt). `DisplayLocale`/`LocalizedLiteral` liegen im Kernel, weil sie ein Wert pro Prozess sind, einmal im Composition Root konfiguriert und in die Bounded Contexts injiziert. Zusaetzlich der pure Helfer `CodeAssignment` (`createRetryingOnCodeCollision`): die von allen vier BCs geteilte "naechsten Business-Code lesen-berechnen, `create()`, bei Kollision neu berechnen"-Schleife, generisch ueber Ressourcentyp und die jeweilige `Duplicate<Typ>CodeException` (#144). Liegt im Kernel und **nicht** in `arknet-persistence-support`, obwohl dort schon geteilte Out-Adapter-Technik wohnt: persistence-support traegt `io.kogn.rdf`-Compile-Deps, und die `*-core`-Services, die den Helfer rufen, muessen RDF-frei bleiben (ArchUnit Regel 3). Der Kernel ist das eine technologieneutrale Modul, an dem jeder `*-core` ohnehin haengt -- so bleibt der Helfer reines JDK und zieht kein RDF auf einen puren Core-Classpath. Bewusst winzig.

`ProjectId` traegt die Invariante `RESERVED_SYSTEM_DATASET` (`urn:arknet:system`, das Dataset der
Projekt-Registry) und lehnt genau diesen Wert im Konstruktor ab. Die Konstante liegt hier und nicht
im `arknet-project-core`, obwohl dort der Lebenszyklus des Bezeichneten verwaltet wird: die vier
Modell-BC-Cores routen auf dieser Identitaet und duerfen nicht an einem Nachbar-BC haengen,
waehrend der Kernel das eine Modul ist, an dem jeder Core ohnehin haengt. Die Form des Werts ist
sonst unbeschraenkt (nur non-blank) -- neue Projekte minten eine UUID, aus der alten Slug-Ableitung
gewachsene Ids wie `arknet` bleiben gueltige opake Werte und werden nie migriert (ADR-016 Punkt 5).

Dazu der Port `ProjectResolver` (`String anchor -> ProjectId`, Konstante `ANCHOR_KEY`): `ProjectId`
ist **kein** Prozess-Singleton, sondern wird von jedem `@McpTool`-Adapter pro Aufruf aus dem Anker
aufgeloest, den der Client mitschickt. Die Aufloesung ist ein Registry-Nachschlagen auf den ganzen,
uninterpretierten Wert -- nichts wird abgeleitet, gekuerzt oder geraten (ADR-016). Die
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
