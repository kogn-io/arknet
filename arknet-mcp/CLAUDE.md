# arknet-mcp

MCP-Server (stdio) + Composition Root -- Spring Boot/Spring AI 2.0, verdrahtet requirements-Hexagon + ubiquitous-language-Hexagon + use-cases-Hexagon + bounded-context-Hexagon als `@McpTool`-Beans. Die BC-Hexagons teilen den `WorkspaceId`-Bean UND einen gemeinsamen `DatasetLifecycle`-Bean -- ein Store pro Workspace. Beherbergt zusaetzlich den generischen, BC-uebergreifenden Store-Lesepfad (`store_overview`/`resource_get`, readOnly; ADR-006) -- Logik in `mcp/store/`, kein eigener BC.
