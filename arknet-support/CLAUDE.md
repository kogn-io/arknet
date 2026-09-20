# arknet-support

Aggregator (`packaging=pom`), kein Bounded Context: buendelt die drei technischen Bibliotheken zwischen den Kontexten.
Kein Modell-Term haengt an einem der drei Module, darum ist keines davon Shared Kernel oder Vokabular.
Er haelt `arknet-persistence-support` (Support der kognio-rdf-Adapter), `arknet-mcp-support` (Support der treibenden MCP-Adapter und des Composition Root) und `arknet-persistence-test-support` (Testseite von `arknet-persistence-support`).
Kein eigenes `dependencyManagement`: die Root-POM fuehrt die Versionen aller drei Module bereits ueber ihre artifactIds.

Detail je Modul: `arknet-persistence-support/CLAUDE.md`, `arknet-mcp-support/CLAUDE.md`, `arknet-persistence-test-support/CLAUDE.md`.
