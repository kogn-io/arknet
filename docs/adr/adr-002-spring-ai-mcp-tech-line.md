# ADR-002: Spring AI 2.0 als Tech-Linie fuer den MCP-Layer

## Status

Akzeptiert (2026-07-13)

## Kontext

arknet ist MCP-first. Zunaechst war der MCP-Layer gegen das rohe MCP SDK
(`io.modelcontextprotocol.sdk`) gebaut: `arknet-mcp` als stdio-Server mit
handgebauten `McpSchema.Tool`/`JsonSchema`-Objekten.

Mit der ersten hexagonalen Bounded-Context-Komponente (requirements, #26) kam fuer den
In-Adapter Spring AI 2.0 (`@McpTool`) hinzu -- angelehnt an das Schwesterprojekt
kognio-memory (`kognio-mcp`), das seinen MCP-Layer bereits mit Spring AI baut.

Folge: zwei MCP-SDK-Generationen im Reaktor -- `mcp-core:1.1.0` (raw, in `arknet-mcp`)
und `mcp-core:2.0.0` (transitiv ueber Spring AI). Das erzwang einen lokalen
Versions-Override und barg ein Dependency-Mediation-Risiko (`NoSuchMethodError` zur
Laufzeit). Aufgeraeumt in #27.

## Entscheidung

**Spring AI 2.0 ist die Technologie-Linie fuer arknets MCP-Layer (Server und Client)** --
nicht mehr pro Modul bewertet, sondern gesetzt. Die fruehere raw-MCP-SDK-Position ist
obsolet.

1. MCP-Tools werden als `@McpTool`/`@McpToolParam` auf Spring-Beans deklariert; Tool-Name,
   Beschreibung und JSON-Input-Schema werden aus Annotation und Methodensignatur
   abgeleitet, nicht handgebaut.
2. `arknet-mcp` ist die Spring-Boot-Composition-Root (stdio, `web-application-type=none`).
   Springs Annotation-Scanner sammelt die `@McpTool`-Beans aller BC-Adapter ein; die
   Composition Root verdrahtet die Hexagone als Beans. Kein manuelles Tool-Spec-Bridging.
3. Kein rohes MCP SDK mehr. Nur die Spring-AI-BOM managed `mcp-core` -- eine Version im
   gesamten Reaktor.
4. Die Client-Seite (Adapter B gegen kognio-memory, ADR-001 Sec. 5) nutzt spaeter dieselbe
   Linie (`org.springaicommunity:mcp-client-security`, Spring).

## Konsequenzen

### Positiv

- Eine SDK-Version im Baum; kein Mediation-Risiko, keine lokalen Overrides fuer mcp-core.
- Weniger Boilerplate: Schema wird aus Signatur abgeleitet, Fehler werden automatisch auf
  Error-Results gemappt.
- Konsistent mit kognio-memory (`kognio-mcp`); der spring-basierte Client-Security-Weg fuer
  Adapter B bleibt offen.

### Negativ / bewusst

- `arknet-mcp` wird eine Spring-Boot-Anwendung (schwerer als ein plain-stdio-Prozess).
- Spring-Boot-Stack-Alignment noetig: die rdf4j-BOM zwingt aeltere
  SLF4J/Logback/jackson-Versionen auf, die den Spring-Boot-Stack brechen. Aktuell per
  lokalem Versions-Override nur in `arknet-mcp/pom.xml` geloest. Bei weiterem Boot-Einzug
  (z.B. `arknet-cli`) zentralisieren.

## Umsetzung

- `requirements-adapter-mcp`: `@McpTool` (#26).
- `arknet-mcp`: raw SDK -> Spring AI 2.0 stdio; `SyncMcpToolProvider`-Bruecke entfernt;
  SDK-Doppelversion aufgeloest (#27, Commit `21125d5`).

## Alternativen verworfen

- **Beim rohen MCP SDK bleiben.** Handgebaute Schemas, kein Bean-Scanning, divergiert von
  kognio-memory und vom spring-basierten Client-Security-Weg. Verworfen.
- **Zwei SDK-Generationen koexistieren lassen.** Doppelte Pflege plus Mediation-Risiko --
  genau der Zustand, den #27 aufgeloest hat. Verworfen.

## Referenzen

- ADR-001 (Editions-Modell und austauschbarer Store), Sec. 5 (Security-Richtung Adapter B).
- Forgejo kogn-io/arknet #26 (requirements-Komponente MVP), #27 (arknet-mcp-Migration,
  erledigt).
- kognio-memory `kognio-mcp` (Vorbild fuer den Bean-Stil; dort webmvc-Transport statt
  stdio).
