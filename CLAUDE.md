# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Repository: Code und Pull Requests leben auf GitHub (`github.com/kogn-io/arknet`, Apache-2.0).
  Der GitHub-Issue-Tracker ist bewusst abgeschaltet; Fragen und Vorschlaege laufen ueber
  GitHub Discussions. Siehe README "Repository"-Abschnitt.

## Architektur

- Pipes & Filters: Turtle → Parse → Validate (SHACL) → Triple Store (RDF4J) → SPARQL → Template → AsciiDoc → HTML/PDF
- Delivery: **MCP-first** + CLI als Convenience-Layer
- Store: lokaler Single-User-Client, Store hinter domaennahem Out-Port austauschbar. Siehe `docs/adr/adr-001-local-client-and-swappable-store.md` (Client+Store), `adr-003-adapter-b-remote-store.md` (Adapter B), `adr-004-spring-ai-mcp-tech-line.md` (Spring-AI-Tech-Linie fuer MCP), `adr-005-store-first-model-lifecycle.md` (Store-first: der Store ist der primaere Modell-Ort; Datei-Pipeline / `arknet_*`-Tools aussterbend), `adr-006-generic-store-read-path.md` (generischer BC-uebergreifender Store-Lesepfad `store_overview`/`resource_get` im Composition Root), `adr-007-shared-shacl-write-gate-module.md` (geteiltes SHACL-Write-Gate als eigenes technisches Modul statt Shared Kernel; grenzt gegen ADR-006 ab), `adr-008-in-adapter-as-bc-gateway.md` (In-Adapter darf einen Nachbar-BC-In-Port konsumieren -- Tor zum BC, nicht Teil von dessen Core; die "kein BC am anderen"-Invariante bindet nur noch die `*-core`; grenzt gegen ADR-006 und ADR-007 ab), `adr-013-shared-write-funnel.md` (geteilter Schreibtrichter WriteFunnel in arknet-persistence-support -- Transaktions-Skelett der kogniordf-Schreibpfade, offengehaltener ADR-011-Ansatzpunkt; ul-Patch-update und req-compareAndUpdate bewusst draussen), `adr-014-revision-als-concurrency-token.md` (Revision als PROV-O-Traeger UND Concurrency-Token -- abfragbarer Head je Ressource, CAS im WriteFunnel; loest ADR-013s Sonderpfade auf statt sie zu integrieren), `adr-015-domaenentypen-bleiben-records.md` (Domaenentypen der BCs bleiben Records -- kein graph-backed Domaenenobjekt, kein Konstruktions-Out-Port; `CONSTRUCT`-Lesepfad adapterintern erlaubt, Service-Merge arbeitet mit Feld-Deltas; die Bauweise der `resource_update`-Fassade bleibt offen)
- CLI: **nicht implementiert**. Produktvision haelt an einem CLI als CI/CD-Convenience-Layer fest -- ein store-first-Neuschnitt, wenn der CI-Bedarf konkret wird.
- Keine Datei-Pipeline: `arknet-core`, `arknet-projection` und die datei-basierten `arknet_*`-MCP-Tools existieren nicht mehr. Store-first (die BC-Tools) ist der einzige Modell-Lebenszyklus (ADR-005). Einziger generierender Ausgabepfad ist das self-contained `store-report.html`.
- MCP-Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Workspaces der Maschine (Streamable HTTP, `127.0.0.1:47331`), nicht mehr ein Claude-Code-Subprozess pro Session -- Grund: mehrere Sessions/Worktrees teilen seit der git-common-dir-basierten WorkspaceId denselben Store, und jeder Subprozess kollidierte am NativeStore-Verzeichnis-Lock, sobald zwei davon gleichzeitig liefen. Welchen Workspace ein Aufruf trifft, kommt pro Aufruf aus dem Startverzeichnis der Session (`.mcp.json`-Header `X-Arknet-Workspace-Dir: ${PWD}`), nicht mehr aus einem beim Boot fixierten Singleton -- darum genuegt ein Port fuer alle Projekte (ADR-009). Details/Start: `arknet-mcp/CLAUDE.md`, `README.md`.
- Repo-Schnitt: `kogn-io/arknet` (dieses Repo, der Service) und `kogn-io/arknet-plugin` (das Claude Code Plugin) sind getrennte Repositories mit unabhaengigen Release-Zyklen/Versionsachsen (ADR-012).

## Tech-Stack

- Java 25+ (`release 25`; io.kogn.rdf ist Java-25-gebaut), Maven Multi-Module
- Bauen/Testen: shell-`mvn` (default JDK 25); JDT-MCP kann `--release 25` (noch) nicht
- RDF4J 6.x (Triple Store + SHACL Sail)
- AsciidoctorJ + asciidoctor-diagram (AsciiDoc → HTML/PDF)
- PlantUML (Diagramme)
- Mustache (Templates fuer AsciiDoc-Generierung)
- Spring AI 2.0 (Tech-Linie fuer den MCP-Layer -- `@McpTool`)
- kognio-rdf (`io.kogn.rdf`, embeddable RDF-Substrat hinter Out-Ports; OSS github.com/kogn-io/rdf-core)
- Turtle (.ttl) als Primaerformat

## Maven-Module

Je Modul liegt die Detail-Doku (Klassen, Ports, Invarianten, ADR-Bezuege) in
einer eigenen `CLAUDE.md` im Modulverzeichnis -- laedt nur, wenn dort auch
gearbeitet wird.

- **arknet-ontology**: nur .ttl-Ressourcen (Ontologie-Module, Shapes). Details: `arknet-ontology/CLAUDE.md`
- **arknet-mcp**: MCP-Server (Streamable HTTP, EIN geteilter lokaler Daemon fuer alle Workspaces auf `127.0.0.1:47331`, admin-gestartet -- kein Claude-Code-Subprozess mehr; Workspace pro Aufruf aus dem `${PWD}`-Header, ADR-009) + Composition Root, verdrahtet alle vier BC-Hexagons + geteilter DatasetLifecycle + generischer Store-Lesepfad (ADR-006). Details: `arknet-mcp/CLAUDE.md`
- **arknet-shared-kernel**: DDD Shared Kernel -- WorkspaceId, WorkspaceResolver-Port (Per-Aufruf-Aufloesung, ADR-009), ResourceId, DisplayLocale/LocalizedLiteral. Details: `arknet-shared-kernel/CLAUDE.md`
- **arknet-persistence-support**: technischer Support der kognio-rdf-Out-Adapter -- geteiltes SHACL-Write-Gate (ADR-007), geteilter Schreibtrichter WriteFunnel (ADR-013), SparqlTerms, UnresolvedReferenceException. Details: `arknet-persistence-support/CLAUDE.md`
- **arknet-architecture-tests**: ArchUnit-Regeln fuer Dependency-Invarianten, die der Modulschnitt nicht erzwingen kann. Details: `arknet-architecture-tests/CLAUDE.md`
- **arknet-requirements**: erste hexagonale BC -- Requirement-Lifecycle (`req_*`-Tools), usesTerm-Kante ins Glossar, opake Identitaet, acceptanceCriterion. Details: `arknet-requirements/CLAUDE.md`
- **arknet-ubiquitous-language**: zweite hexagonale BC -- SKOS-Glossar (`term_*`-Tools) mit optionaler Actor-Facette, opake Identitaet. Details: `arknet-ubiquitous-language/CLAUDE.md`
- **arknet-use-cases**: dritte hexagonale BC -- Cockburn-Use-Cases (`uc_*`-Tools) mit opaken Step-VOs, Actor-/Requirement-Referenzen. Details: `arknet-use-cases/CLAUDE.md`
- **arknet-bounded-context**: vierte hexagonale BC -- BoundedContext-Lifecycle (`bc_*`-Tools), ubiquitousLanguageTerm-Kante ins Glossar (BC->Term, #62), opake Identitaet. Details: `arknet-bounded-context/CLAUDE.md`

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

## Claude Code Plugin

Skills (`/arknet:adr`, `/arknet:req-interview`) leben in einem separaten Repository,
[`kogn-io/arknet-plugin`](https://github.com/kogn-io/arknet-plugin) -- Plugin und
Service releasen unabhaengig voneinander (ADR-012). Die Root-`.mcp.json` dieses
Repos bleibt fuers eigene Dogfooding gegen den hier gebauten MCP-Server bestehen.

Sprachkonvention fuer nicht-code Artefakte, die dieses Repo ausliefert (SHACL
`sh:message`, Ontologie-Labels, Maven-`<description>`): **Englisch** -- die
Zielgruppe ist englischsprachig. Was **in den Store** geschrieben wird
(Requirement-/Use-Case-/Term-Text) bleibt **Deutsch** -- arknets eigene
Ubiquitous Language, unberuehrt von dieser Regel. Die ADRs unter `docs/adr/`
sind **konventionsgemaess Deutsch** (Entscheidungsprotokolle, keine geshippte
Plugin-Flaeche) -- kein Uebersetzungs-Rueckstand. Offener Englisch-Rueckstand auf
geshippten Flaechen: die SHACL-`sh:message` und ein Teil der Ontologie-Labels.

## Regel fuer diese Datei

`CLAUDE.md` wird mit ausgeliefert. Hier steht nur, was ein Fremder auch aus dem
Code ableiten koennte: Struktur, Konventionen, Invarianten, Bauanleitung.
**Nicht hierher gehoeren:** Repo-Sichtbarkeit und Zugangswege, interne
Infrastruktur (Hostnames, Tracker, Marketplaces), Editions-/Preis-/
Monetarisierungsfragen, Herkunft aus nicht-oeffentlichen Vorprojekten sowie
alles mit Status- oder Zeitbezug ("derzeit", "noch nicht gepusht"). Das gilt
fuer die Modul-`CLAUDE.md` genauso.
