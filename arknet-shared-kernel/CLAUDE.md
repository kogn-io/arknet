# arknet-shared-kernel

DDD Shared Kernel -- technologieneutrale, von mehreren BCs geteilte Domain-Bausteine: `de.hauschel.arknet.kernel.WorkspaceId` sowie die opake Ressourcen-Identitaet `ResourceId` (sealed, `of(String)` wrapt eine bestehende `https://`-IRI und validiert im Konstruktor vollstaendig: `https://`-Prefix, keine Whitespace UND keine der IRIREF-verbotenen Zeichen `<>"{}|^\`\`+Steuerzeichen -- dieselbe Zeichenregel wie `SparqlTerms.isValidIriReference` in `arknet-persistence-support`, notwendig dupliziert, da der Kernel nicht am Support haengen darf), `ResourceIdFactory`-Port + `UuidResourceIdFactory` (mintet flach unter `https://w3id.org/arknet/id/<uuid>`, kein BC-/Typ-Segment -- der Typ lebt in `rdf:type`). Zusaetzlich `DisplayLocale` (Record `requested`/`systemDefault: Locale`, statische `DEFAULT`-Instanz, Methode `select(Collection<LocalizedLiteral>)`) und `LocalizedLiteral` (Record `value`/`languageTag`, technologieneutrale Projektion eines sprachgetaggten RDF-Literals) -- die Anzeige-Sprachauswahl fuer mehrsprachige `skos:prefLabel`-Literale, ueber eine vierstufige Fallback-Kette: angefordertes Locale -> System-Default -> ungetaggtes Literal -> deterministischer Fallback ueber eine stabile Sortierung (nie ein hartes `FILTER`, das eine Ressource ohne Label in der angeforderten Sprache stumm verschluckt). `DisplayLocale`/`LocalizedLiteral` liegen im Kernel, weil sie ein Wert pro Prozess sind, einmal im Composition Root konfiguriert und in die Bounded Contexts injiziert. Bewusst winzig.

Seit dem geteilten HTTP-Daemon (#137, ADR-009) zusaetzlich der Port `WorkspaceResolver`
(`String originDir -> WorkspaceId`): `WorkspaceId` ist **kein** Prozess-Singleton mehr, sondern
wird von jedem `@McpTool`-Adapter pro Aufruf aus dem Herkunftsverzeichnis des Clients aufgeloest
(die Composition-Root-Implementierung `GitWorkspaceResolver` in `arknet-mcp` macht die
Git-Ableitung/Slugging/Cache-Arbeit). Shared-Kernel-Grund derselbe wie bei `WorkspaceId` selbst:
mehrere BCs (requirements, ubiquitous-language, ...) adressieren denselben Workspace-Begriff und
teilen sich einen Weg, ihn aufzuloesen, statt jeder seinen eigenen zu erfinden.
