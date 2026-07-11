# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Produktvision: `docs/produktvision.adoc`

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

Konsolidiert aus drei Projekten. Die Originale lagen als Archiv unter
`doc42-origin/`; dieses wurde nach abgeschlossener doc42-/dddprocess-Migration
entfernt und ist bei Bedarf ueber die Git-Historie wiederherstellbar
(Import-Commit `139bb86 chore: import doc42 project as doc42-origin archive`).

| Projekt | Wird zu | Beitrag | Status |
|---------|---------|---------|--------|
| doc42 | arknet-core/cli/projection | Pipeline, Walking Skeleton | fertig |
| dddprocess | arknet-ontology (core + process) | Prozesse, State Machines, DDD-Bausteine | portiert (22/24 Klassen; abstrakte `DomainObject`/`Message` bewusst weggelassen) |
| ddd-forge | arknet-ontology (privacy-Modul) | DSGVO-Ontologie | portiert (`arknet-privacy.ttl` + `privacy-shapes.ttl` aus ddd-forge.ttl re-lizenziert; DPV-aligned. Noch NICHT in `ModelLoader` verdrahtet -- wie `arknet-process.ttl` derzeit inaktiv) |
