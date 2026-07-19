# arknet -- Architecture Knowledge Net

DDD-Architekturmodelle, die Maschinen verstehen.

W3C-Standards (RDF/OWL) statt proprietaerer DSL -- validierbar (SHACL), querybar (SPARQL), AI-ready (MCP).

## Voraussetzungen

- Java 25+
- Maven 3.9+

## Claude Code Plugin

```bash
claude --plugin-dir /path/to/arknet
```

### Voraussetzung: MCP-Server-Daemon starten

Der arknet-MCP-Server ist ein einzelner, langlebiger Prozess pro Workspace (Streamable HTTP auf
`127.0.0.1:47331`) -- **kein** Subprozess, den Claude Code selbst startet. Ein HTTP-Eintrag in
`.mcp.json` ist bei Claude Code rein passiv: es verbindet sich nur zur URL, startet oder verwaltet
aber nichts. Vor der ersten Nutzung also einmalig selbst starten, z.B.:

```bash
mvn -pl arknet-mcp -am package -DskipTests
java -jar arknet-mcp/target/arknet-mcp-*.jar
```

Ein fertiges Docker-Image (kein lokales Java/Maven noetig) ist als Zieldistribution geplant, aber
noch nicht gebaut. Solange der Prozess laeuft, koennen beliebig viele Claude-Code-Sessions (auch
parallele Worktrees desselben Workspace) sich denselben Store teilen, ohne sich am
NativeStore-Verzeichnis-Lock zu blockieren. Laeuft der Daemon nicht, meldet Claude Code die
MCP-Verbindung als fehlgeschlagen.

Mehrere arknet-Workspaces auf derselben Maschine beanspruchen aktuell denselben festen Port und
kollidieren -- workspace-spezifische Ports sind noch nicht automatisiert.

### MCP-Tools

Requirements-BC (`arknet-requirements`) -- Requirement-Lifecycle:

| Tool | Beschreibung |
|------|-------------|
| `req_add` | Requirement anlegen (funktional / nicht-funktional) |
| `req_list` | Alle verwalteten Requirements auflisten |
| `req_get` | Einzelnes Requirement per Identitaet holen (z.B. FR-1, NFR-7) |
| `req_set_status` | Lebenszyklus-Status aendern (PROPOSED / ACCEPTED) |
| `req_link_term` | Requirement mit einem Glossar-Begriff verknuepfen (`arkreq:usesTerm`; Term muss existieren) |
| `req_schema` | `arkreq:`-Vokabular (RequirementType, RequirementStatus, Priority) als Daten -- Definition + zulaessige Werte, damit ein Client nicht raten muss |

Ubiquitous-Language-BC -- Glossar-Begriffe (SKOS Concepts):

| Tool | Beschreibung |
|------|-------------|
| `term_add` | Neuen Glossar-Begriff anlegen (mintet ein SKOS Concept; optional als Actor markierbar via `actorKind`/`actorRole`) |
| `term_list` | Alle Glossar-Begriffe auflisten |
| `term_get` | Einzelnen Begriff per Identitaet holen (z.B. TERM-1) |

Use-Cases-BC (`arknet-use-cases`) -- flow-orientierte Cockburn-Use-Cases (binden FR ueber einen Interaktionsablauf):

| Tool | Beschreibung |
|------|-------------|
| `uc_add` | Kompletten Use Case in einem Call anlegen (Goal, Actor, Trigger, nummerierter Step-Flow mit FR-Referenzen) |
| `uc_list` | Alle Use Cases auflisten |
| `uc_get` | Einzelnen Use Case mit aufgeloesten Steps und FR-/Actor-Kanten holen (z.B. UC1) |

Bounded-Context-BC (`arknet-bounded-context`) -- BoundedContext-Lifecycle (ordnet Glossar-Begriffe einem fachlichen Schnitt zu):

| Tool | Beschreibung |
|------|-------------|
| `bc_add` | Neuen Bounded Context anlegen |
| `bc_list` | Alle Bounded Contexts auflisten |
| `bc_get` | Einzelnen Bounded Context mit verlinkten Glossar-Begriffen holen |
| `bc_link_term` | Bounded Context mit einem Glossar-Begriff verknuepfen (`arknet:hasAggregate`; Term muss existieren) |

Store-Report -- generischer, BC-uebergreifender Lesepfad (readOnly; funktioniert fuer jede BC ohne Typ-Mapping):

| Tool | Beschreibung |
|------|-------------|
| `store_overview` | Kompakter Text-Digest des Workspace-Stores (Prefix-Legende, Typ-Zaehler, Entity-Zeilen mit `resource_get`-Drill-down, Integritaets-Hinweis) + schreibt einen self-contained HTML-Resource-Browser und gibt den Pfad zurueck |
| `resource_get` | Alle Triples einer Ressource (aus- und eingehend); Handle als CURIE (`req:FR-1`), volle IRI oder bare Business-Id (`FR-1`) |

Traceability -- readOnly Graph-Traversierung ueber denselben Store-Snapshot (kein zweiter SPARQL-Pfad):

| Tool | Beschreibung |
|------|-------------|
| `trace_matrix` | Pro Requirement (FR/NFR): genutzte Glossar-Begriffe (`arkreq:usesTerm`) und realisierende Use Case(s) (ueber den Step-Flow) |
| `orphan_check` | Verwaiste Artefakte: Requirements ohne realisierenden Use Case, Glossar-Begriffe ohne jede Verwendung |
| `impact_analysis` | Transitive "wer referenziert das"-Huelle fuer einen Ressourcen-Handle -- was ist betroffen, wenn sich X aendert |

### Speichermodell (store-first)

Das Modell lebt primaer im lokalen RDF-Store (kognio-rdf), **persistent ueber Sessions hinweg** -- nicht in-memory und nicht in einer Turtle-Datei. Pro Workspace (= Projekt, abgeleitet aus dem Git-Top-Level bzw. Arbeitsverzeichnis) haelt der Store ein isoliertes Dataset; Default-Ablage `~/.arknet/rdf`, konfigurierbar via `arknet.rdf.storage`.

**Modell verwalten:** ueber die store-basierten BC-Tools (`req_*`, `term_*`, `uc_*`) -- nicht durch Text-Edits an einer `.ttl`. SHACL-Validierung greift einheitlich am Write-Gate des Stores: ein ungueltiger Schreibvorgang wird abgelehnt, nichts wird persistiert.

Die vormals geduldeten datei-basierten `arknet_*`-Tools (`arknet_load`/`arknet_validate`/`arknet_query`/`arknet_generate` aus einer `.ttl`) wurden entfernt -- store-first ist der einzige Modell-Lebenszyklus, keine parallele Datei-Wahrheit mehr. Hintergrund: [ADR-005](docs/adr/adr-005-store-first-model-lifecycle.md) inkl. Nachtrag.

## Module

| Modul | Beschreibung |
|-------|-------------|
| `arknet-ontology` | OWL-Ontologie und SHACL-Shapes (nur .ttl Ressourcen, kein Java) |
| `arknet-mcp` | MCP-Server (Streamable HTTP, lokaler Daemon) + Composition Root: verdrahtet die BC-Hexagons (requirements / ubiquitous-language / use-cases / bounded-context) ueber einen geteilten DatasetLifecycle + den generischen Store-Report (`store_overview`/`resource_get`) + den Traceability-Lesepfad (`trace_matrix`/`orphan_check`/`impact_analysis`) |
| `arknet-shared-kernel` | DDD Shared Kernel: von mehreren BCs geteilte Domain-Bausteine (`WorkspaceId`, opake `ResourceId`/`ResourceIdFactory`) |
| `arknet-persistence-support` | Technischer Support der kognio-rdf-Out-Adapter: das geteilte SHACL-Write-Gate (validate-before-commit) |
| `arknet-requirements` | Erste hexagonale BC: Requirement-Lifecycle (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |
| `arknet-ubiquitous-language` | Zweite hexagonale BC: Glossar-Begriffe als SKOS-Concepts (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |
| `arknet-use-cases` | Dritte hexagonale BC: flow-orientierte Cockburn-Use-Cases (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |
| `arknet-bounded-context` | Vierte hexagonale BC: BoundedContext-Lifecycle, ordnet Glossar-Begriffe einem fachlichen Schnitt zu (core + Out-Adapter kognio-rdf + In-Adapter MCP/Spring AI) |
| `arknet-architecture-tests` | ArchUnit-Regeln fuer die Dependency-Invarianten, die der Modulschnitt nicht erzwingen kann (nur `src/test`, kein Produktivcode) |

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

Pipes & Filters (Produktvision, siehe `docs/produktvision.adoc`; **nicht implementiert** -- kein generierender Ausgabepfad, siehe [ADR-005](docs/adr/adr-005-store-first-model-lifecycle.md)):

```
Turtle (.ttl) -> Parse -> Validate (SHACL) -> Triple Store (RDF4J) -> SPARQL -> Mustache -> AsciiDoc -> HTML/PDF
```

Gelebt wird heute store-first (MCP-Write-Tools -> RDF4J-Store -> generischer Lesepfad `store_overview`/`resource_get`, s.o.).

## Herkunft

Konsolidiert aus drei Projekten:

- **doc42** -- Walking Skeleton (Java/RDF4J Pipeline)
- **dddprocess** -- DDD Process Ontology (Zustandsmaschinen, Gap Analysis)
- **ddd-forge** -- Claude Plugin (DSGVO-Ontologie, AI-Skills)

## Lizenz

Proprietary. All rights reserved.
