# ADR-004: Spring AI 2.0 als Tech-Linie fuer den MCP-Layer

- Status: Proposed (2026-07-13)
- Verwandt: ADR-002, ADR-003, ADR-009 (MCP-Transport -- wechselt den Transport-Baustein
  innerhalb dieser Tech-Linie von stdio auf HTTP, aendert nichts an der Annotation-basierten
  SDK-Generation, die diese ADR entscheidet)

## Kontext

arknet ist MCP-first. Zunaechst war der MCP-Layer gegen das rohe MCP SDK
(`io.modelcontextprotocol.sdk`) gebaut: `arknet-mcp` als stdio-Server mit handgebauten
`McpSchema.Tool`/`JsonSchema`-Objekten. Mit der ersten hexagonalen Bounded-Context-
Komponente (requirements) kam fuer den In-Adapter Spring AI 2.0 (`@McpTool`) hinzu. Folge:
zwei MCP-SDK-Generationen im Reaktor -- `mcp-core:1.1.0` (raw, in `arknet-mcp`) und
`mcp-core:2.0.0` (transitiv ueber Spring AI). Das erzwang einen lokalen Versions-Override
und barg ein Dependency-Mediation-Risiko (`NoSuchMethodError` zur Laufzeit).

## Entscheidung

**Spring AI 2.0 ist die Technologie-Linie fuer arknets MCP-Layer (Server und Client)** --
nicht mehr pro Modul bewertet, sondern gesetzt. Die fruehere raw-MCP-SDK-Position ist
obsolet.

1. MCP-Tools werden als `@McpTool`/`@McpToolParam` auf Spring-Beans deklariert; Tool-Name,
   Beschreibung und JSON-Input-Schema werden aus Annotation und Methodensignatur abgeleitet,
   nicht handgebaut.
2. `arknet-mcp` ist die Spring-Boot-Composition-Root. Springs Annotation-Scanner sammelt
   die `@McpTool`-Beans aller BC-Adapter ein; die Composition Root verdrahtet die Hexagone
   als Beans. Kein manuelles Tool-Spec-Bridging. Der konkrete Transport (stdio vs. HTTP)
   ist ADR-009s Entscheidung, nicht dieser.
3. Kein rohes MCP SDK mehr. Nur die Spring-AI-BOM managed `mcp-core` -- eine Version im
   gesamten Reaktor.

## Konsequenzen

**Positiv:** Eine SDK-Version im Baum; kein Mediation-Risiko, keine lokalen Overrides fuer
mcp-core. Weniger Boilerplate: Schema wird aus der Signatur abgeleitet, Fehler werden
automatisch auf Error-Results gemappt.

**Negativ / bewusst:** `arknet-mcp` wird eine Spring-Boot-Anwendung (schwerer als ein
plain-stdio-Prozess). Spring-Boot-Stack-Alignment noetig: die rdf4j-BOM und der
Spring-Boot-Stack fordern divergierende SLF4J/Logback/jackson-Versionen -- dieses Alignment
muss zentral getragen werden, sobald mehr als ein Modul Spring Boot einzieht.

## Alternativen

- **Beim rohen MCP SDK bleiben.** Handgebaute Schemas, kein Bean-Scanning, divergiert vom
  spring-basierten Client-Security-Weg (ADR-003). Verworfen.
- **Zwei SDK-Generationen koexistieren lassen.** Doppelte Pflege plus Mediation-Risiko.
  Verworfen.
