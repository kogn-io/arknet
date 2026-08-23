# neu-07: Spring AI ist die Technologie-Linie des MCP-Layers

- Status: Proposed (2026-08-23) -- ersetzt ADR-004

## Kontext

arknets einzige Auslieferungsflaeche ist MCP. Die Frage ist, ob Tool-Deklaration und
Transport gegen das rohe MCP-SDK gebaut werden -- mit handgeschriebenen Tool-Spezifikationen
und JSON-Schemata -- oder gegen eine Rahmenbibliothek.

Die Zahl der Tools waechst mit jedem Ressourcentyp. Handgebaute Schemata sind bei drei Tools
Nebensache und bei vierzig eine eigene Fehlerquelle, die niemand testet.

## Entscheidung

Spring AI ist die Technologie-Linie fuer arknets MCP-Layer, als Server wie als Client.

1. Tools werden als `@McpTool`/`@McpToolParam` auf Spring-Beans deklariert. Name,
   Beschreibung und Eingabeschema werden aus Annotation und Methodensignatur abgeleitet, nicht
   handgebaut.
2. `arknet-mcp` ist die Spring-Boot-Composition-Root. Der Annotation-Scanner sammelt die
   Tool-Beans der Kontext-Adapter ein; die Composition Root verdrahtet die Hexagone.
3. Kein rohes MCP-SDK. Die Spring-AI-BOM managed die MCP-Abhaengigkeit -- eine Version im
   gesamten Reaktor.

Der Transport ist damit **nicht** entschieden; das ist neu-08.

## Konsequenzen

**Positiv:** Tool-Schema und Methodensignatur koennen nicht auseinanderlaufen, weil es nur
eine Quelle gibt. Neue Tools kosten eine annotierte Methode.

**Negativ:** arknet haengt an einer Rahmenbibliothek, deren MCP-Unterstuetzung juenger ist als
das Protokoll. Wo sie eine Protokollfaehigkeit nicht abbildet oder unsicher vorbelegt, muss
arknet nachbessern statt auszuweichen -- neu-08 enthaelt genau so einen Fall. Spring Boot
zieht ausserdem eine Startzeit und einen Speicherbedarf mit, die ein reiner SDK-Server nicht
haette.

## Alternativen

- **Rohes MCP-SDK.** Verworfen -- handgebaute Schemata skalieren nicht mit der Toolzahl.
- **Pro Modul entscheiden.** Verworfen -- fuehrt zu zwei MCP-Stacks im selben Reaktor.
