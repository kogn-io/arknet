# arknet-mcp

MCP-Server (Streamable HTTP, Spring AI 2.0 `spring-ai-starter-mcp-server-webmvc`) + Composition Root -- Spring Boot, verdrahtet requirements-Hexagon + ubiquitous-language-Hexagon + use-cases-Hexagon + bounded-context-Hexagon als `@McpTool`-Beans. Die BC-Hexagons teilen den `WorkspaceId`-Bean UND einen gemeinsamen `DatasetLifecycle`-Bean -- ein Store pro Workspace. Beherbergt zusaetzlich den generischen, BC-uebergreifenden Store-Lesepfad (`store_overview`/`resource_get`, readOnly; ADR-006) -- Logik in `mcp/store/`, kein eigener BC. Daneben der Traceability-Lesepfad (`trace_matrix`/`orphan_check`/`impact_analysis`, readOnly) in `mcp/trace/`: `TraceabilityGraph` baut aus genau derselben `StoreReader#readSnapshot`-Momentaufnahme einen In-Memory-Graphen (kein zweiter, bespoke SPARQL-Pfad) und traversiert `arkreq:usesTerm`/`primaryActor`/`supportingActor`/`stepRealises` sowie den `mainStep`/`extensionStep`-Hop -- ein `arkreq:Step`-Knoten wird dabei durchquert (fuer den Hop zur besitzenden UseCase), aber nie selbst als Treffer gemeldet, da er ein aggregat-internes Value Object ohne eigene Identitaet ist. `impact_analysis` teilt sich `resource_get`s Handle-Vertrag (CURIE/IRI/bare-id) ueber den dafuer aus `StoreReportTools` gezogenen `HandleResolver`, statt ihn zu duplizieren.

Betriebsmodell: ein langlebiger Daemon pro Workspace auf `127.0.0.1:47331` (Loopback-only, daher
ohne Authentifizierung) statt eines stdio-Subprozesses pro Claude-Code-Session -- Grund: mehrere
Sessions/Worktrees teilen seit der git-common-dir-basierten WorkspaceId denselben Store und
kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock. Ein HTTP-Eintrag in
`.mcp.json` ist bei Claude Code rein passiv (nur Verbindungsaufbau, kein Prozess-Spawn/-Management)
-- Start und Betrieb des Daemons sind Sache des Menschen, siehe `README.md`. ADR-001/ADR-004
beschreiben noch das alte stdio-Modell; Nachtrag offen. Offen bleibt auch ein Konfigurationsweg
fuer workspace-spezifische Ports (`arknet.mcp.port` ist als Property vorbereitet, aber nichts
leitet ihn automatisch aus dem Workspace ab) -- mehrere arknet-Workspaces auf derselben Maschine
wuerden aktuell denselben Port beanspruchen.
