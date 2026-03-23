# arknet -- Architecture Knowledge Net

DDD-Architekturmodelle, die Maschinen verstehen.

W3C-Standards (RDF/OWL) statt proprietaerer DSL -- validierbar (SHACL), querybar (SPARQL), AI-ready (MCP).

## Voraussetzungen

- Java 21+
- Maven 3.9+

## Claude Code Plugin

```bash
# Als Plugin verwenden (baut automatisch beim ersten Start)
claude --plugin-dir /path/to/arknet

# Skill: Architektur-Analyse eines bestehenden Projekts
/arknet:analyze
```

### MCP-Tools

| Tool | Beschreibung |
|------|-------------|
| `arknet_load` | Turtle-Modell in Triple Store laden |
| `arknet_validate` | SHACL-Validierung (Violations + Warnings) |
| `arknet_query` | SPARQL-Query ausfuehren (frei oder vordefiniert Q01-Q20) |
| `arknet_list_queries` | Vordefinierte Queries auflisten |
| `arknet_generate` | Context-Map als HTML/PDF generieren |

### Speichermodell

Der Triple Store (RDF4J) laeuft **in-memory** und lebt nur solange die Claude-Session aktiv ist. Persistent ist nur die Turtle-Datei:

```
projekt/
  architecture-model.ttl    <-- generiert von /arknet:analyze, versionierbar mit Git
```

**Erste Analyse:** `/arknet:analyze` analysiert den Code und schreibt `architecture-model.ttl`.

**Naechste Session:** `arknet_load architecture-model.ttl` laedt das bestehende Modell. Kein Neuanalysieren noetig -- nur Weiterarbeiten (Queries, Gap Analysis, Doku).

**Modell aktualisieren:** `/arknet:analyze` erneut ausfuehren oder `.ttl` manuell editieren, dann `arknet_validate`.

## CLI

```bash
java -jar arknet-cli/target/arknet-cli-0.1.0-SNAPSHOT.jar validate examples/order-domain.ttl
java -jar arknet-cli/target/arknet-cli-0.1.0-SNAPSHOT.jar generate --input examples/order-domain.ttl --output docs
```

## Module

| Modul | Beschreibung |
|-------|-------------|
| `arknet-ontology` | OWL-Ontologie und SHACL-Shapes (nur .ttl Ressourcen, kein Java) |
| `arknet-core` | RDF4J Triple Store, SPARQL-Execution, SHACL-Validierung |
| `arknet-projection` | Mustache-Templates + AsciidoctorJ Pipeline (Turtle -> AsciiDoc -> HTML/PDF) |
| `arknet-mcp` | MCP-Server (stdio) -- macht die Engine fuer AI-Agenten querybar |
| `arknet-cli` | PicoCLI-Einstiegspunkt: `validate`, `generate` |

## Ontologie

Modularer Aufbau unter dem Namespace `https://w3id.org/arknet/`:

| Modul | Prefix | Konzepte |
|-------|--------|----------|
| `arknet-core.ttl` | `arknet:` | BoundedContext, Aggregate, Entity, ValueObject, Command, DomainEvent, ContextMap |
| `arknet-process.ttl` | `arkproc:` | Process, Step, State, StateTransition, BusinessRule, Outcome, Actor |
| `arknet-architecture.ttl` | `arkarch:` | Architecture, View, Viewpoint, ADR, Stakeholder (geplant) |
| `arknet-tech.ttl` | `arktech:` | Service, Container, API, Database (geplant) |
| `arknet-privacy.ttl` | `arkpriv:` | DataCategory, LegalBasis, ProcessingPurpose (geplant) |

## Architektur

Pipes & Filters:

```
Turtle (.ttl) -> Parse -> Validate (SHACL) -> Triple Store (RDF4J) -> SPARQL -> Mustache -> AsciiDoc -> HTML/PDF
```

## Herkunft

Konsolidiert aus drei Projekten:

- **doc42** -- Walking Skeleton (Java/RDF4J Pipeline)
- **dddprocess** -- DDD Process Ontology (Zustandsmaschinen, Gap Analysis)
- **ddd-forge** -- Claude Plugin (DSGVO-Ontologie, AI-Skills)

## Lizenz

Proprietary. All rights reserved.
