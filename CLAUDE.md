# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Produktvision: `doc42-origin/research/produktvision.adoc`

## Architektur

- Pipes & Filters: Turtle → Parse → Validate (SHACL) → Triple Store (RDF4J) → SPARQL → Template → AsciiDoc → HTML/PDF
- Delivery: **MCP-first** + CLI als Convenience-Layer
- CLI: `arknet validate`, `arknet generate`, `arknet query`

## Tech-Stack

- Java 21+, Maven Multi-Module
- RDF4J 5.x (Triple Store + SHACL Sail)
- PicoCLI (CLI)
- AsciidoctorJ + asciidoctor-diagram (AsciiDoc → HTML/PDF)
- PlantUML (Diagramme)
- Mustache (Templates fuer AsciiDoc-Generierung)
- Turtle (.ttl) als Primaerformat

## Maven-Module

- **arknet-ontology**: nur .ttl-Ressourcen (Ontologie-Module, Shapes)
- **arknet-core**: RDF4J, SPARQL, SHACL-Validierung
- **arknet-projection**: Template-Engine, View-Plugins
- **arknet-cli**: PicoCLI, orchestriert core + projection
- **arknet-mcp**: MCP-Server (spaetere Phase)

## Ontologie-Namespaces

- **Basis:** `https://w3id.org/arknet/`
- `https://w3id.org/arknet/core#` (Prefix: `arknet:`) — BoundedContext, Aggregate, Entity, ValueObject, Command, DomainEvent, ContextMap
- `https://w3id.org/arknet/process#` (Prefix: `arkproc:`) — Process, Step, StateTransition, BusinessRule, Outcome, Actor
- `https://w3id.org/arknet/architecture#` (Prefix: `arkarch:`) — Architecture, View, Viewpoint, ADR, Stakeholder (ISO 42010)
- `https://w3id.org/arknet/tech#` (Prefix: `arktech:`) — Service, Container, API, Database, MessageBroker
- `https://w3id.org/arknet/privacy#` (Prefix: `arkpriv:`) — DataCategory, LegalBasis, ProcessingPurpose

## Ubiquitous Language

- **Metamodell** = OWL-Ontologie (arknet-*.ttl)
- **Architekturmodell** = Instanzdaten des Nutzers (.ttl)
- **Projektion** = generiertes Artefakt (AsciiDoc/HTML/PDF, PlantUML, Turtle)
- **Viewpoint** = SPARQL-Query + Template + Rolle
- **Shape** = SHACL-Validierungsregel

## Konventionen

- Java-Package: `de.hauschel.arknet.*`
- GroupId: `de.hauschel.arknet`
- Turtle als Primaerformat (nicht JSON-LD)
- SHACL-Validierung bei jedem Load (RDF4J SHACL Sail)
- Projektionen als Plugin: `.sparql` + Template-Datei-Paar

## Herkunft

Konsolidiert aus drei Projekten (Originale in `doc42-origin/`):

| Projekt | Wird zu | Beitrag |
|---------|---------|---------|
| doc42 | arknet-core/cli/projection | Pipeline, Walking Skeleton |
| dddprocess | arknet-ontology (process-Modul) | Prozesse, State Machines, Gap Analysis |
| ddd-forge | arknet-ontology (privacy-Modul, spaeter) | DSGVO-Ontologie |
