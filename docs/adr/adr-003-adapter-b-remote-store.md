# ADR-003: Adapter B -- remote Store gegen kognio-memory (+ Security-Richtung)

- Status: Proposed (2026-07-14) -- Richtungsentscheidung; kein Code in arknet, bis Adapter B ansteht
- Verwandt: ADR-001, ADR-002, ADR-004

## Kontext

ADR-001 haelt den Store hinter einem domaennahen Out-Port austauschbar und benennt
Adapter B als remote Variante: ein MCP-Client gegen kognio-memory, das Team / Multi-Project
/ Auth selbst traegt. Adapter B bleibt ein separates, nicht mit ausgeliefertes Modul, bis der
Bedarf konkret wird. Dieses ADR haelt fest, *wie* Adapter B angebunden und abgesichert wird --
als Richtung, noch nicht als Umsetzung.

## Entscheidung

1. **Anbindung.** Adapter B (`requirements-adapter-kogniomemory`) implementiert den
   domaennahen Out-Port als MCP-Client gegen kognio-memory. arknet ist damit zugleich
   MCP-Server (fuer den treibenden Agenten) und MCP-Client (fuer den Store). Der Adapter
   uebersetzt Domaenenobjekte (`Requirement` etc.) auf kognio-memory-Aufrufe; die
   **WorkspaceId** (ADR-001, Invariante) wird zum Project-Selektor.

2. **Security.** Adapter B authentifiziert als MCP-Client per OAuth 2.0 nach dem
   Spring-Muster fuer MCP-Client-Security (`org.springaicommunity:mcp-client-security`) --
   dieselbe Spring-AI-Tech-Linie wie der Server (ADR-004).
   Voraussetzung: das Backend spricht serverseitig OAuth2 -- wird gebaut, wenn Adapter B
   ansteht. Bis dahin kein Auth-Code in arknet.

## Konsequenzen

**Positiv:** Team-Faehigkeit als reiner Adapter-Austausch, ohne arknets Core mit
Auth/Tenancy zu belasten. Der Security-Weg bleibt konsistent mit der Spring-AI-Linie
(ADR-004).

**Negativ / offen:** Haengt an backend-seitigem OAuth2 -- eine Vorbedingung ausserhalb
arknets. Als Multi-Writer-Pfad verlangt Adapter B SHACL-Validierung transaktional, nicht
nur standalone.

## Alternativen

- **Triple-/GraphStore-Protokoll statt domaennahem MCP-Client.** Wuerde arknets RDF-Interna
  ueber die Leitung exponieren und die Austauschbarkeit (ADR-001) aufweichen. Verworfen.
- **ApiKey statt OAuth2 fuer den Client.** Einfacher, aber nicht die Spring-AI-Linie
  (ADR-004) und schwaecher fuer Multi-Actor-Szenarien. Vorerst verworfen (revidierbar,
  wenn Adapter B tatsaechlich gebaut wird).
