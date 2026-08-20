# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Repository: Code und Pull Requests leben auf GitHub (`github.com/kogn-io/arknet`, Apache-2.0).
  Bugs/Feature-Requests laufen ueber den GitHub-Issue-Tracker (Label-Schema
  `prio:*`, Typ-Labels (`bug`/`enhancement`/`chore`/`refactor`/`documentation`/`vision`/...),
  `deferred`), offene Fragen ueber GitHub Discussions. Siehe README
  "Repository"-Abschnitt.

## Architektur

- Pipes & Filters: Turtle → Parse → Validate (SHACL) → Triple Store (RDF4J) → SPARQL → Template → AsciiDoc → HTML/PDF
- Delivery: **MCP-first** + CLI als Convenience-Layer
- Store: lokaler Single-User-Client, Store hinter domaennahem Out-Port austauschbar.
Siehe `docs/adr/adr-001-local-client-and-swappable-store.md` (Client+Store), `adr-003-adapter-b-remote-store.md` (Adapter B), `adr-004-spring-ai-mcp-tech-line.md` (Spring-AI-Tech-Linie fuer MCP), `adr-005-store-first-model-lifecycle.md` (Store-first: der Store ist der primaere Modell-Ort; Datei-Pipeline / `arknet_*`-Tools aussterbend), `adr-006-generic-store-read-path.md` (generischer BC-uebergreifender Store-Lesepfad `store_overview`/`resource_get` im Composition Root), `adr-007-shared-shacl-write-gate-module.md` (geteiltes SHACL-Write-Gate als eigenes technisches Modul statt Shared Kernel; grenzt gegen ADR-006 ab), `adr-008-in-adapter-as-bc-gateway.md` (In-Adapter darf einen Nachbar-BC-In-Port konsumieren -- Tor zum BC, nicht Teil von dessen Core; die "kein BC am anderen"-Invariante bindet nur noch die `*-core`; grenzt gegen ADR-006 und ADR-007 ab), `adr-009-mcp-http-daemon-transport.md` (MCP-Transport als ein geteilter HTTP-Daemon auf Loopback fuer alle Projekte statt stdio-Subprozess pro Session), `adr-010-review-ui-vaadin-oss-adapter.md` (Review-UI als read-only Vaadin-Flow-OSS-Adapter), `adr-011-commit-provenance-statt-diffbarem-export.md` (Traceability ueber Commit-Provenance statt diffbarem Datei-Export), `adr-012-plugin-service-repository-split.md` (Plugin `arknet-plugin` und Service `arknet` in getrennten Repositories mit eigenen Versionsachsen statt einem Monorepo), `adr-013-shared-write-funnel.md` (geteilter Schreibtrichter WriteFunnel in arknet-persistence-support -- Transaktions-Skelett der kogniordf-Schreibpfade, offengehaltener ADR-011-Ansatzpunkt), `adr-014-revision-als-concurrency-token.md` (Revision als PROV-O-Traeger UND Concurrency-Token -- abfragbarer Head je Ressource, CAS im WriteFunnel; loest ADR-013s Sonderpfade auf statt sie zu integrieren), `adr-015-domaenentypen-bleiben-records.md` (Domaenentypen der BCs bleiben Records -- kein graph-backed Domaenenobjekt, kein Konstruktions-Out-Port; `CONSTRUCT`-Lesepfad adapterintern erlaubt, Merge oberhalb des Out-Ports arbeitet mit Feld-Deltas statt Objekt-Snapshots), `adr-016-projekt-identitaet-ueber-registrierte-anker.md` (Store-Identitaet wird registriert statt abgeleitet -- der Client sendet einen opaken, typisierten Anker, ein Dataset haelt die Daten genau eines Projekts, unbekannter Anker ist ein Fehler statt eines Defaults; loest die Herkunft aus ADR-001 und Punkt 3 von ADR-009 ab), `adr-017-iso-15288-als-scope-orientierung.md` (Ontologie-Scope orientiert sich an ISO/IEC/IEEE 15288s Technical-Process-Gruppe als Rahmen, nicht als Implementierungsziel), `adr-019-requirement-status-beidseitig-setzbar.md` (Requirement-Status bleibt ein unverbindliches Reifegrad-Signal ohne Durchsetzung, ist aber in beide Richtungen setzbar; Ausbau mit echten Konsequenzen ist zurueckgestellt, offene Vorarbeit dafuer in #289 -- loest ADR-018 ab)
- CLI: **nicht implementiert**. Produktvision haelt an einem CLI als CI/CD-Convenience-Layer fest -- ein store-first-Neuschnitt, wenn der CI-Bedarf konkret wird.
- Keine Datei-Pipeline: `arknet-core`, `arknet-projection` und die datei-basierten `arknet_*`-MCP-Tools existieren nicht mehr. Store-first (die BC-Tools) ist der einzige Modell-Lebenszyklus (ADR-005). Einziger generierender Ausgabepfad ist das self-contained `store-report.html`.
- MCP-Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Projekte der Maschine (Streamable HTTP, `127.0.0.1:47331`), kein Claude-Code-Subprozess pro Session -- Grund: mehrere Sessions/Worktrees desselben Projekts teilen einen Store und kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock.
Welches Projekt ein Aufruf trifft, entscheidet der Anker, den der Client pro Aufruf mitschickt (`.mcp.json`-Header `X-Arknet-Project-Anchor: ${PWD}`, alternativ der optionale `projectAnchor`-Parameter jedes Tools) und den der Server ausschliesslich nachschlaegt -- darum genuegt ein Port fuer alle Projekte (ADR-009/ADR-016).
Details/Start: `arknet-mcp/CLAUDE.md`, `README.md`.
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
- **arknet-mcp**: MCP-Server (Streamable HTTP, EIN geteilter lokaler Daemon fuer alle Projekte auf `127.0.0.1:47331`, admin-gestartet -- kein Claude-Code-Subprozess; Projekt pro Aufruf ueber den Anker aus dem Header, ADR-009/ADR-016) + Composition Root, verdrahtet alle sieben BC-Hexagons + geteilter DatasetLifecycle + der Anker-Aufloesung ueber die Projekt-Registry + generischer Store-Lesepfad (ADR-006; der Agent-Digest bleibt generisch, der HTML-Report wird pro BC aus deren Lese-In-Ports zusammengesetzt und faellt fuer alles Uebrige auf die generische Rohsicht zurueck).
Details: `arknet-mcp/CLAUDE.md`
- **arknet-shared-kernel**: DDD Shared Kernel -- ProjectId (inkl. der reservierten System-Dataset-Invariante), ProjectResolver-Port (Per-Aufruf-Aufloesung ueber den Anker, ADR-009/ADR-016), ResourceId, DisplayLocale/LocalizedLiteral. Details: `arknet-shared-kernel/CLAUDE.md`
- **arknet-persistence-support**: technischer Support der kognio-rdf-Out-Adapter -- geteiltes SHACL-Write-Gate (ADR-007), geteilter Schreibtrichter WriteFunnel (ADR-013; schreibt je Write atomar eine PROV-O-Revision + Head-Pointer, ADR-014), SparqlTerms, UnresolvedReferenceException, die Vokabular-Konstanten ArkprovVocabulary/ArkreqVocabulary/ArkdddVocabulary/ArkprjVocabulary/ArkarchVocabulary.
Details: `arknet-persistence-support/CLAUDE.md`
- **arknet-persistence-test-support**: Test-Support derselben kognio-rdf-Out-Adapter -- die geteilten Dekoratoren GuardedLifecycle/GuardedHandle/GuardSyncTx fuer DatasetLifecycle/DatasetHandle/DatasetTx, mit denen die `*RealStoreConcurrencyTest` der BCs eine gewaehlte Verschraenkung zweier Schreiber gegen den echten On-Disk-Store festnageln; gewoehnliches main-Scope-Artefakt (kein `test-jar`-Classifier), von den Konsumenten im test-Scope gezogen.
Details: `arknet-persistence-test-support/CLAUDE.md`
- **arknet-architecture-tests**: Invarianten, die der Modulschnitt nicht erzwingen kann -- ArchUnit-Dependency-Regeln plus der beidseitige Abgleich von `ArkprovVocabulary`/`ArkprjVocabulary`/`ArkarchVocabulary` gegen die ausgelieferte Provenance-, Projekt- bzw. Architektur-Ontologie. Details: `arknet-architecture-tests/CLAUDE.md`
- **arknet-requirements**: erste hexagonale BC -- Requirement-Lifecycle (`req_*`-Tools), usesTerm-Kante ins Glossar (`arkreq:usesTerm`, seit Issue #329 auch von UseCase aus setzbar, die Kante bleibt aber requirements-BC-eigen), opake Identitaet, acceptanceCriterion.
Traegt zusaetzlich Constraint als zweiten Ressourcentyp desselben Hexagons (`constraint_add`/`constraint_get`/`constraint_list`/`constraint_update`, `req_link_constraint` fuer die `oslc_rm:constrainedBy`-Kante) -- technische/geschaeftliche/regulatorische Randbedingungen, TCON-/BCON-/RCON-Codes; Typ und Code stehen mit der Anlage fest, Titel und Statement sind korrigierbar und mehrsprachig.
Details: `arknet-requirements/CLAUDE.md`
- **arknet-ubiquitous-language**: zweite hexagonale BC -- SKOS-Glossar (`term_*`-Tools) mit optionaler Actor-Facette, opake Identitaet. Details: `arknet-ubiquitous-language/CLAUDE.md`
- **arknet-use-cases**: dritte hexagonale BC -- Cockburn-Use-Cases (`uc_*`-Tools) mit opaken Step-VOs, Actor-/Requirement-Referenzen.
Traegt seit Issue #329 zusaetzlich `usesTerm`-Kanten ins Glossar (`uc_link_term`) und `constrainedBy`-Kanten zu Constraints der requirements-BC (`uc_link_constraint`), damit ein rein use-case-gefuehrtes Projekt (Requirement optional, Issue #327) strukturell vernetzt bleibt.
Details: `arknet-use-cases/CLAUDE.md`
- **arknet-bounded-context**: vierte hexagonale BC -- BoundedContext-Lifecycle (`bc_*`-Tools), ubiquitousLanguageTerm-Kante ins Glossar (BC->Term), `arkddd:ContextRelationship` als eigene Ressource fuer die gerichtete Context-Mapping-Kante zwischen zwei Bounded Contexts (`bc_link_context`), opake Identitaet. Details: `arknet-bounded-context/CLAUDE.md`
- **arknet-project**: fuenfte hexagonale BC -- die Projekt-Registry (`project_*`-Tools), die einen vom Client gesendeten, opaken und typisierten Anker auf das Projekt abbildet, dessen Dataset die Modelldaten haelt (ADR-016).
Verwaltet Identitaet statt Modell und ist als einziger BC nicht projekt-scoped: seine Registry wohnt im reservierten System-Dataset.
Sie ist die Aufloesungsquelle, auf die jeder Tool-Aufruf der sechs Modell-BCs geroutet wird.
Details: `arknet-project/CLAUDE.md`
- **arknet-adr**: sechste hexagonale BC -- Architecture-Decision-Record-Lifecycle (`adr_*`-Tools), `addressesRequirement`-Kante zu den Requirements, `affectsContext`-Kante zu den Bounded Contexts, selbstbezuegliche `supersedes`-Kante (nur die Vorwaertsrichtung wird als Tripel geschrieben), opake Identitaet.
Der store-first-Lebenszyklus fuer ADRs (ADR-005) steht neben den handgepflegten Markdown-ADRs unter `docs/adr/`; die beiden Nummernraeume sind unabhaengig voneinander.
Details: `arknet-adr/CLAUDE.md`
- **arknet-actor**: siebte hexagonale BC -- Actor-Lifecycle (`actor_*`-Tools), `ACTOR-N`-Codes aus einem Zaehler fuer alle vier Typen (`HUMAN`/`SYSTEM`/`LEGAL`/`GROUP`; Typ und Code stehen mit der Anlage fest, Name und Beschreibung sind korrigierbar), opake Identitaet.
Macht `arkproc:Actor` zu einer eigenstaendigen Ressource statt zu einer Facette am Glossarbegriff: ein Akteur braucht weder Definition noch `TERM-N`-Code, darf aber zusaetzlich Glossarbegriff sein.
Name und Beschreibung sind bewusst ungetaggte Literale ohne Mehrsprachigkeits-Mechanismus, und der Hexagon traegt keine Cross-BC-Kante -- die Actor-Facette der ubiquitous-language-BC laeuft unveraendert weiter, und in diesem Schnitt zeigt noch kein Konsument hierher.
Details: `arknet-actor/CLAUDE.md`

## Ontologie-Namespaces

- **Basis:** `https://w3id.org/arknet/`
- `https://w3id.org/arknet/core#` (Prefix: `arknet:`) — generisches Utility-Vokabular (name, description, ...), wiederverwendbar in jedem Modul
- `https://w3id.org/arknet/ddd#` (Prefix: `arkddd:`) — BoundedContext, Domain, Subdomain, ContextRelationship, RelationshipType (Live, `arknet-ddd.ttl`, von arknet-bounded-context genutzt); ContextMap sowie das taktische DDD (Aggregate, Entity, ValueObject, Command, DomainEvent, ...) bleiben geparkt (`parked/arknet-ddd_parked.ttl`, kein BC), teilen sich aber den Namespace
- `https://w3id.org/arknet/process#` (Prefix: `arkproc:`) — Actor (Unterklasse von `prov:Agent`)/HumanActor/SystemActor/LegalActor/GroupActor/actorRole (Live, `arknet-actor.ttl`, von arknet-actor als eigenstaendige Ressource geschrieben und von arknet-use-cases/arknet-ubiquitous-language als Term-Facette genutzt); Process, Step, StateTransition, BusinessRule, Outcome bleiben geparkt (`parked/arknet-process.ttl`, kein BC)
- `https://w3id.org/arknet/requirements#` (Prefix: `arkreq:`) — Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release (OSLC-RM-aligned, doap:Version)
- `https://w3id.org/arknet/architecture#` (Prefix: `arkarch:`) — ArchitectureDecisionRecord samt Textfeldern, Relationen (`supersedes`/`supersededBy`, `relatedTo`, `addressesRequirement`, `affectsContext`) und den fuenf ADRStatus-Individuen (Live, `arknet-architecture.ttl`, von arknet-adr genutzt); die uebrige ISO-42010-Architekturbeschreibung (Architecture, ArchitectureDescription, Stakeholder, Concern, Viewpoint, View) bleibt geparkt (`parked/arknet-architecture_parked.ttl`, kein BC), teilt sich aber den Namespace
- `https://w3id.org/arknet/tech#` (Prefix: `arktech:`) — Service, Container, API, Database, MessageBroker
- `https://w3id.org/arknet/privacy#` (Prefix: `arkpriv:`) — DataCategory, LegalBasis, ProcessingPurpose
- `https://w3id.org/arknet/provenance#` (Prefix: `arkprov:`) — Revision (PROV-O-basiert), head (Head-Pointer = Concurrency-Token je Ressource; ADR-014)
- `https://w3id.org/arknet/project#` (Prefix: `arkprj:`) — Project (registrierte Store-Identitaet), Anchor + AnchorType (`PathAnchor`/`UrlAnchor`/`UuidAnchor`); Praefix bewusst `arkprj` statt `arkproj`, um die Verwechslung mit `arkproc:` in SPARQL-Queries auszuschliessen (ADR-016)

## Ubiquitous Language

- **Metamodell** = OWL-Ontologie (arknet-*.ttl)
- **Architekturmodell** = Instanzdaten des Nutzers (.ttl)
- **Shape** = SHACL-Validierungsregel
- **Projekt** = der Gegenstand, an dem Requirements, Glossarbegriffe und Use Cases haengen; ein
  Dataset haelt die Daten genau eines Projekts, die Projektgrenze ist damit die Datengrenze
  (ADR-016). **Nicht** das Verzeichnis, aus dem ein Client arbeitet -- das heisst Arbeits- oder
  Projektverzeichnis. Ein Workspace-Begriff darueber existiert nicht.
- **Anker** = die opake, typisierte Zeichenkette (`path`/`url`/`uuid`), mit der ein Client sagt,
  welches Projekt sein Aufruf meint. Der Server schlaegt sie nach und interpretiert sie nie. Ein
  Projekt haelt mehrere Anker, ein Anker gehoert zu genau einem Projekt.

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

## Claude Code Plugin

Skills (`/arknet:adr`, `/arknet:req-interview`) leben in einem separaten Repository,
[`kogn-io/arknet-plugin`](https://github.com/kogn-io/arknet-plugin) -- Plugin und
Service releasen unabhaengig voneinander (ADR-012). Die Root-`.mcp.json` dieses
Repos bleibt fuers eigene Dogfooding gegen den hier gebauten MCP-Server bestehen.

Sprachkonvention fuer nicht-code Artefakte, die dieses Repo ausliefert
(Maven-`<description>`): **Englisch** -- die Zielgruppe ist englischsprachig.
**Ausnahme Ontologie-Beschriftungen:** `rdfs:label`, `rdfs:comment` und SHACL-
`sh:message` in den `arknet-*.ttl`/`*-shapes.ttl` sind **zweisprachig**, Englisch
zuerst (`rdfs:label "Actor Role"@en , "Akteursrolle"@de`) -- der Bestand ist so
gewachsen, und Konsistenz innerhalb einer Datei wiegt hier schwerer als die
Englisch-Regel. Neue Beschriftungen also immer `@en` **und** `@de` anlegen;
vorhandene einsprachige nicht nachtraeglich vereinheitlichen. Was **in den Store**
geschrieben wird, ist fuer die zentralen benannten/beschreibenden Felder nativ
**mehrsprachig**: Glossarbegriffe (`term_add`/`term_update`), die optionale
Projektbeschreibung (`project_add`/`project_update`), Requirement-`title`/
`description`/AcceptanceCriterion-`text` (`req_add`/`req_update`) und
UseCase-`title`/`goal`/`scope`/`trigger`/`precondition`/`postcondition`/
Step-`text`/Extension-`text` (`uc_add`/`uc_update`) sowie Constraint-`title`/
`constraintStatement` (`constraint_add`/`constraint_update`) tragen jeweils
mehrere sprachgetaggte RDF-Literale je Ressource, ueber ein optionales
`language`-Argument beim Schreiben gesetzt und beim Lesen ueber die
`DisplayLocale`-Fallback-Kette aufgeloest. Andere Freitext-Felder (z.B. die
ADR-Textfelder, BoundedContext-`name`/`description`) bleiben einfache,
ungetaggte Literale ohne diesen Mechanismus. Die ADRs unter `docs/adr/`
sind **konventionsgemaess Deutsch** (Entscheidungsprotokolle, keine geshippte
Plugin-Flaeche) -- kein Uebersetzungs-Rueckstand.

## Regel fuer diese Datei

`CLAUDE.md` wird mit ausgeliefert. Hier steht nur, was ein Fremder auch aus dem
Code ableiten koennte: Struktur, Konventionen, Invarianten, Bauanleitung.
**Nicht hierher gehoeren:** Repo-Sichtbarkeit und Zugangswege, interne
Infrastruktur (Hostnames, Tracker, Marketplaces), Editions-/Preis-/
Monetarisierungsfragen, Herkunft aus nicht-oeffentlichen Vorprojekten sowie
alles mit Status- oder Zeitbezug ("derzeit", "noch nicht gepusht"). Das gilt
fuer die Modul-`CLAUDE.md` genauso.
