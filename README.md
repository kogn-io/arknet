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
```

### MCP-Tools

| Tool | Beschreibung |
|------|-------------|
| `arknet_load` | Turtle-Modell in Triple Store laden |
| `arknet_validate` | SHACL-Validierung (Violations + Warnings) |
| `arknet_query` | SPARQL-Query ausfuehren (frei oder vordefiniert Q01-Q20) |
| `arknet_list_queries` | Vordefinierte Queries auflisten |
| `arknet_generate` | Projektion als HTML/PDF generieren (Default: context-map) |
| `arknet_list_projections` | Verfuegbare Projektionstypen auflisten |

Requirements-BC (`arknet-requirements`) -- Requirement-Lifecycle:

| Tool | Beschreibung |
|------|-------------|
| `req_add` | Requirement anlegen (funktional / nicht-funktional) |
| `req_list` | Alle verwalteten Requirements auflisten |
| `req_get` | Einzelnes Requirement per Identitaet holen (z.B. FR-1, NFR-7) |
| `req_set_status` | Lebenszyklus-Status aendern (PROPOSED / ACCEPTED) |

Ubiquitous-Language-BC -- Glossar-Begriffe (SKOS Concepts):

| Tool | Beschreibung |
|------|-------------|
| `term_add` | Neuen Glossar-Begriff anlegen (mintet ein SKOS Concept) |
| `term_list` | Alle Glossar-Begriffe auflisten |
| `term_get` | Einzelnen Begriff per Identitaet holen (z.B. TERM-1) |

### Speichermodell

Der Triple Store (RDF4J) laeuft **in-memory** und lebt nur solange die Claude-Session aktiv ist. Persistent ist nur die Turtle-Datei:

```
projekt/
  architecture-model.ttl    <-- dein Architekturmodell, versionierbar mit Git
```

**Modell laden:** `arknet_load architecture-model.ttl` laedt das Modell in den Store.

**Modell aktualisieren:** `.ttl` manuell editieren, dann `arknet_validate`.

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
| `arknet-shared-kernel` | DDD Shared Kernel: von mehreren BCs geteilte Domain-Bausteine (`WorkspaceId`) |
| `arknet-requirements` | Erste hexagonale BC: Requirement-Lifecycle (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |
| `arknet-ubiquitous-language` | Zweite hexagonale BC: Glossar-Begriffe als SKOS-Concepts (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |

## Ontologie

Modularer Aufbau unter dem Namespace `https://w3id.org/arknet/`:

| Modul | Prefix | Konzepte |
|-------|--------|----------|
| `arknet-core.ttl` | `arknet:` | BoundedContext, Aggregate, Entity, ValueObject, Command, DomainEvent, ContextMap |
| `arknet-process.ttl` | `arkproc:` | Process, Step, State, StateTransition, BusinessRule, Outcome, Actor |
| `arknet-requirements.ttl` | `arkreq:` | Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release |
| `arknet-architecture.ttl` | `arkarch:` | Architecture, View, Viewpoint, ADR, Stakeholder, Concern |
| `arknet-tech.ttl` | `arktech:` | Service, Container, API, Database (geplant) |
| `arknet-privacy.ttl` | `arkpriv:` | DataCategory, LegalBasis, ProcessingPurpose, DataSubjectRight, TechnicalMeasure, PrivacyImpactAssessment |

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
