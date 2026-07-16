# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Produktvision: `docs/produktvision.adoc`

## Architektur

- Pipes & Filters: Turtle → Parse → Validate (SHACL) → Triple Store (RDF4J) → SPARQL → Template → AsciiDoc → HTML/PDF
- Delivery: **MCP-first** + CLI als Convenience-Layer
- Editionen/Store: lokaler Single-User-Client, Store hinter domaennahem Out-Port austauschbar (kognio-rdf lokal = Community/OSS <-> kognio-memory remote = Closed). Siehe `docs/adr/adr-001-local-client-and-swappable-store.md` (Client+Store), `adr-002-open-core-editions.md` (Editionen), `adr-003-adapter-b-remote-store.md` (Adapter B), `adr-004-spring-ai-mcp-tech-line.md` (Spring-AI-Tech-Linie fuer MCP), `adr-005-store-first-model-lifecycle.md` (Store-first: der Store ist der primaere Modell-Ort; Datei-Pipeline / `arknet_*`-Tools aussterbend), `adr-006-generic-store-read-path.md` (generischer BC-uebergreifender Store-Lesepfad `store_overview`/`resource_get` im Composition Root), `adr-007-shared-shacl-write-gate-module.md` (geteiltes SHACL-Write-Gate als eigenes technisches Modul statt Shared Kernel; grenzt gegen ADR-006 ab)
- CLI: derzeit **nicht implementiert**. Das datei-basierte `arknet-cli`-Modul (PicoCLI, `validate`/`generate` aus einer `.ttl`) wurde entfernt, weil es auf der von ADR-005 abgeloesten Datei-Pipeline sass. Die Produktvision haelt an einem CLI als CI/CD-Convenience-Layer fest -- ein store-first-Neuschnitt, wenn der CI-Bedarf konkret wird.

## Tech-Stack

- Java 21+, Maven Multi-Module
- RDF4J 6.x (Triple Store + SHACL Sail)
- AsciidoctorJ + asciidoctor-diagram (AsciiDoc → HTML/PDF)
- PlantUML (Diagramme)
- Mustache (Templates fuer AsciiDoc-Generierung)
- Spring AI 2.0 (Tech-Linie fuer den MCP-Layer -- `@McpTool`)
- kognio-rdf (`io.kogn.rdf`, embeddable RDF-Substrat hinter Out-Ports; OSS github.com/kogn-io/rdf-core)
- Turtle (.ttl) als Primaerformat

## Maven-Module

- **arknet-ontology**: nur .ttl-Ressourcen (Ontologie-Module, Shapes)
- **arknet-core**: RDF4J, SPARQL, SHACL-Validierung
- **arknet-projection**: Template-Engine, View-Plugins
- **arknet-mcp**: MCP-Server (stdio) + Composition Root -- Spring Boot/Spring AI 2.0, verdrahtet arknet-Engine + requirements-Hexagon + ubiquitous-language-Hexagon + use-cases-Hexagon als `@McpTool`-Beans (die BC-Hexagons teilen den WorkspaceId-Bean UND einen gemeinsamen DatasetLifecycle-Bean -- ein Store pro Workspace, siehe #48/#49). Beherbergt zusaetzlich den generischen, BC-uebergreifenden Store-Lesepfad (`store_overview`/`resource_get`, readOnly; #47/ADR-006) -- Logik in `mcp/store/`, kein eigener BC
- **arknet-shared-kernel**: DDD Shared Kernel -- technologieneutrale, von mehreren BCs geteilte Domain-Bausteine (`de.hauschel.arknet.kernel.WorkspaceId`). Seit #68 zusaetzlich die opake Ressourcen-Identitaet: `ResourceId` (sealed, `of(String)` wrapt eine bestehende `https://`-IRI), `ResourceIdFactory`-Port + `UuidResourceIdFactory` (mintet flach unter `https://w3id.org/arknet/id/<uuid>`, kein BC-/Typ-Segment -- der Typ lebt in `rdf:type`). Bewusst winzig.
- **arknet-persistence-support**: technischer Support der kognio-rdf-Out-Adapter -- das von allen BCs geteilte SHACL-Write-Gate (`de.hauschel.arknet.persistence.ShaclWriteGate` + `WriteConstraintViolationException`, validate-before-commit; #52, vorher je BC kopiert). Seit #63/PR #67 wohnen hier zwei weitere technologieneutrale, RDF4J-freie Bausteine: `SparqlTerms` (SPARQL-Escaping inkl. LF/CR/TAB + IRIREF-Serialisierung `isValidIriReference`/`iriRef`) und `UnresolvedReferenceException` (didaktische Cross-BC-Ablehnung, vorher je BC kopiert). Das Gate traegt zusaetzlich einen `enforce(candidate, assertedContext)`-Overload: validation-only Kontext-Tripel (synthetische Typen fuer Nachbar-Graph-Knoten) als **Parameter** -- weiterhin kein Kontextwissen im Gate, ADR-007-konform (Nachtrag dort). Bewusst KEIN Shared Kernel: der Kernel wird von den `*-core` konsumiert und muss dependency-frei bleiben, das Gate nur von den `*-adapter-kogniordf` -- ein Merge zoege die kognio-rdf-Ports in den Classpath der Cores. Die Trennung ist lasttragend, nicht Geschmack (hier wohnt zudem Technik, kein Domain-Vokabular). Trotz kognio-rdf-Abhaengigkeit RDF4J-frei -- das Gate kennt nur die `io.kogn.rdf.shacl`/`terms`-Ports, RDF4J bleibt in den Repository-Factories. Kontextunterschiede sind Konstruktor-Parameter (`shapes`/`axioms`/`options`), **kein Code im Gate**: req reasont ueber die Axiome (`subClassOf`, volle Shapes), ul uebergibt leere Axiome + `defaults()` (kein Reasoning), uc konstruiert Axiome/Options identisch zu req -- sein echter Unterschied sind **gefilterte** Shapes (`loadUseCaseShapes()` entfernt fremde `sh:targetClass`), und diese Filterung ist Code in der uc-Factory. Siehe ADR-007.
- **arknet-architecture-tests**: ArchUnit-Regeln fuer die Dependency-Invarianten, die der Modulschnitt NICHT erzwingen kann und die sonst lautlos erodieren (#60). Nur `src/test`, kein Produktivcode, von niemandem konsumiert. Drei Regeln: `arknet-persistence-support` bleibt RDF4J-frei (die Eigenschaft, die ADR-007 traegt), nur die `KognioRdf*RepositoryFactory` nennt RDF4J-Typen (modulintern -- Maven kann das prinzipiell nicht sehen), die `*-core` bleiben frei von `rdf4j`/`io.kogn`. Bewusst NICHT abgebildet: was der Modulschnitt schon haerter erzwingt (core haengt nicht am Adapter, kein BC am anderen) -- das waere Zeremonie. Die Regeln lesen Bytecode, kein POM: eine ungenutzte `rdf4j-*`-Dependency bleibt unsichtbar (`maven-enforcer` waere das ergaenzende Werkzeug, bewusst out of scope).
- **arknet-requirements**: erste hexagonale BC -- arknet-requirements-core (Domaene/In-/Out-Ports) + arknet-requirements-adapter-kogniordf (Out) + arknet-requirements-adapter-mcp (In, Spring AI `@McpTool`). Requirement-Lifecycle. Seit #36 traegt ein Requirement zusaetzlich `arkreq:usesTerm`-Kanten auf Glossar-Begriffe (`req_link_term`): die Kante gehoert bewusst der requirements-BC (Domain `arkreq:Requirement`), damit die Abhaengigkeit requirements -> ubiquitous-language zeigt und nicht umgekehrt. Sie steckt **im** `Requirement`-Record, nicht daneben -- der Out-Adapter schreibt replace-by-identity, eine Kante ausserhalb des Aggregats wuerde der naechste `req_set_status` still loeschen. Aufloesung des Terms streng per `dcterms:identifier` (nicht per Label, damit die Kante Umbenennungen ueberlebt), didaktische Ablehnung bei Unbekanntem -- Bauart 1:1 zur uc-BC. Seit #68 opake Identitaet statt schema-on-subjects: `RequirementId` wrapt eine vom Kernel gemintete `ResourceId` (Subject-IRI, unveraenderlich), `RequirementCode` traegt die bisherige `FR-N`/`NFR-N`-Semantik als reines Business-Label (`dcterms:identifier`) -- der MCP-Nutzer tippt weiterhin den Code, nie die IRI. Entsprechend zerfaellt `save` in `create` (Identitaet darf noch nicht existieren, sonst `ResourceAlreadyExistsException`) und `update` (Identitaet muss existieren, sonst `RequirementNotFoundException`); der Out-Adapter prueft das per `ASK` **innerhalb** der Schreibtransaktion (kein TOCTOU), `update` bleibt danach replace-by-identity wie bisher. Lesen ist weiter Code-first (`findByCode`).
- **arknet-ubiquitous-language**: zweite hexagonale BC (Bauart 1:1 zu requirements) -- arknet-ubiquitous-language-core + arknet-ubiquitous-language-adapter-kogniordf (Out) + arknet-ubiquitous-language-adapter-mcp (In). Glossar-Begriffe als SKOS-Concepts (`term_add`/`term_list`/`term_get`); `arknet:ubiquitousLanguageTerm` ist seit #32 ein `skos:Concept` (nicht mehr `xsd:string`). Seit #45 kann ein Term optional eine Actor-Facette tragen (derselbe Concept zusaetzlich `arkproc:HumanActor`/`SystemActor` + `actorRole`; `term_add` mit optionalem `actorKind`/`actorRole`) -- Pre-Req der Use-Case-BC. Dogfood.
- **arknet-use-cases**: dritte hexagonale BC (Bauart 1:1 zu requirements, #41) -- arknet-use-cases-core + arknet-use-cases-adapter-kogniordf (Out) + arknet-use-cases-adapter-mcp (In). Flow-orientierte Cockburn-Use-Cases als `arkreq:UseCase` mit strukturiertem Step-Flow (`uc_add`/`uc_list`/`uc_get`); coarse-grained write (ein `uc_add`-Call) / fine-grained read; strenge Cross-BC-Referenz-Aufloesung (FR per `dcterms:identifier`, Actor per `skos:prefLabel`) mit didaktischer Ablehnung bei Unbekanntem.

## Ontologie-Namespaces

- **Basis:** `https://w3id.org/arknet/`
- `https://w3id.org/arknet/core#` (Prefix: `arknet:`) — BoundedContext, Aggregate, Entity, ValueObject, Command, DomainEvent, ContextMap
- `https://w3id.org/arknet/process#` (Prefix: `arkproc:`) — Process, Step, StateTransition, BusinessRule, Outcome, Actor
- `https://w3id.org/arknet/requirements#` (Prefix: `arkreq:`) — Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release (OSLC-RM-aligned, doap:Version)
- `https://w3id.org/arknet/architecture#` (Prefix: `arkarch:`) — Architecture, View, Viewpoint, ADR, Stakeholder, Concern (ISO 42010)
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
- Modulverzeichnis == artifactId (ausnahmslos), und **jedes** Modul traegt das
  `arknet-`-Prefix -- auch BC-Submodule (`arknet-mcp`, `arknet-requirements`,
  `arknet-requirements-core`, `arknet-ubiquitous-language-adapter-mcp`).
  Keine Abkuerzungen im Modulnamen: der BC-Name wird ausgeschrieben
  (`arknet-ubiquitous-language-core`, nicht `ul-core`). Java-Packages duerfen
  weiterhin kuerzen (`de.hauschel.arknet.ul.*`) -- die Regel gilt fuer Modul-
  und Artefaktnamen, nicht fuer Packages.
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
| ddd-forge | arknet-ontology (privacy-Modul) | DSGVO-Ontologie | portiert (`arknet-privacy.ttl` + `privacy-shapes.ttl` aus ddd-forge.ttl re-lizenziert; DPV-aligned. Seit 2026-07-11 in `ModelLoader` verdrahtet -- alle 5 Module (core/process/requirements/architecture/privacy) werden geladen und gegen ihre Shapes validiert) |
