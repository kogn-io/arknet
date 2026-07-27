# arknet-mcp

MCP-Server (Streamable HTTP, Spring AI 2.0 `spring-ai-starter-mcp-server-webmvc`) + Composition Root -- Spring Boot, verdrahtet requirements-Hexagon + ubiquitous-language-Hexagon + use-cases-Hexagon + bounded-context-Hexagon als `@McpTool`-Beans. Die BC-Hexagons teilen den `WorkspaceResolver`-Bean UND einen gemeinsamen `DatasetLifecycle`-Bean -- ein Store pro Workspace, aber die WorkspaceId wird pro Tool-Aufruf aufgeloest (nicht mehr als Singleton injiziert), damit ein Prozess alle Workspaces bedienen kann (ADR-009). Beherbergt zusaetzlich den generischen, BC-uebergreifenden Store-Lesepfad (`store_overview`/`resource_get`, readOnly; ADR-006) -- Logik in `mcp/store/`, kein eigener BC. Daneben der Traceability-Lesepfad (`trace_matrix`/`orphan_check`/`impact_analysis`, readOnly) in `mcp/trace/`: `TraceabilityGraph` baut aus genau derselben `StoreReader#readSnapshot`-Momentaufnahme einen In-Memory-Graphen (kein zweiter, bespoke SPARQL-Pfad) und traversiert `arkreq:usesTerm`/`primaryActor`/`supportingActor`/`stepRealises` sowie den `mainStep`/`extensionStep`-Hop -- ein `arkreq:Step`-Knoten wird dabei durchquert (fuer den Hop zur besitzenden UseCase), aber nie selbst als Treffer gemeldet, da er ein aggregat-internes Value Object ohne eigene Identitaet ist. `impact_analysis` teilt sich `resource_get`s Handle-Vertrag (CURIE/IRI/bare-id) ueber den dafuer aus `StoreReportTools` gezogenen `HandleResolver`, statt ihn zu duplizieren. `StoreReader#readSnapshot` klammert den Provenance-Graphen (`ArkprovVocabulary.PROVENANCE_GRAPH`, ADR-014) aus -- der Snapshot speist Report und Traceability als Modell-Sicht, nicht als Aenderungshistorie, und der Revisions-Trail waechst mit jedem Write; `resource_get` outgoing bleibt ungefiltert (genau ein `arkprov:head` je Ressource, begrenzt und Signal), `incoming` filtert dieselbe Ausnahme-Logik eine Ebene tiefer: nichts, was nur im Provenance-Graphen lebt -- jede Revision zeigt via `prov:specializationOf` auf ihre Ressource und wuerde die Nachbarliste je Write um eine Zeile wachsen lassen --, ausser `arkprov:head`, der als einziges Provenance-Tripel etwas ueber die betrachtete Ressource sagt statt ueber ihre Historie.

Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Workspaces der Maschine auf
`127.0.0.1:47331` (Loopback-only, daher ohne Authentifizierung) statt eines stdio-Subprozesses pro
Claude-Code-Session -- Grund: mehrere Sessions/Worktrees teilen seit der git-common-dir-basierten
WorkspaceId denselben Store und kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock.
Welchen Workspace ein Aufruf trifft, kommt pro Aufruf aus dem `.mcp.json`-Header
`X-Arknet-Workspace-Dir: ${PWD}`: `WorkspaceHttpTransportConfiguration` ueberschreibt den
Spring-AI-Transport-Provider mit einem `contextExtractor`, der den Header in den
`McpTransportContext` legt; die `*McpTools` lesen ihn dort pro Call und loesen ihn ueber den
`WorkspaceResolver`-Bean (`GitWorkspaceResolver` -> `WorkspaceIdResolver`, git-common-dir wie
zuvor) auf. Ein Aufruf ohne Header faellt auf das Daemon-Arbeitsverzeichnis zurueck. Der Header
ist Workspace-Routing, keine Authentifizierung (ADR-009). Weil der Workspace pro Aufruf aus dem
Header kommt, genuegt ein Port fuer alle Projekte. Ein HTTP-Eintrag in `.mcp.json` ist bei Claude
Code rein passiv (nur Verbindungsaufbau, kein Prozess-Spawn/-Management) -- Start und Betrieb des
Daemons sind Sache des Menschen, siehe `README.md`.
