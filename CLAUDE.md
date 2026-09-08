# arknet

## Projekt

- **arknet** (Architecture + Knowledge Net) — DDD-Architekturmodelle, die Maschinen verstehen
- Status: Konsolidierung aus doc42 + dddprocess + ddd-forge
- Repository: Code und Pull Requests leben auf GitHub (`github.com/kogn-io/arknet`, Apache-2.0).
  Bugs/Feature-Requests laufen ueber den GitHub-Issue-Tracker (Label-Schema
  `prio:*`, Typ-Labels (`bug`/`enhancement`/`chore`/`refactor`/`documentation`/`vision`/...),
  `deferred`), offene Fragen ueber GitHub Discussions. Milestones sind Release-Schnitte und
  heissen `X.Y.Z Thema` (Version vorne, Thema hinten, z.B. `0.7.0 Stabil+Sprache`); nicht
  jedes Issue traegt einen -- ein Epic, das mehrere Milestones spannt oder vor ihnen laeuft,
  bleibt milestone-frei. Siehe README "Repository"-Abschnitt.

## Architektur

- Pipes & Filters: Turtle → Parse → Validate (SHACL) → Triple Store (RDF4J) → SPARQL → Template → AsciiDoc → HTML/PDF
- Delivery: **MCP-first** + CLI als Convenience-Layer
- Store: lokaler Single-User-Client, Store hinter domaennahem Out-Port austauschbar.
Arknets eigene Architekturentscheidungen sind Records im arknet-Store (`adr_*`-Tools des Moduls arknet-adr); `docs/adr-export/` ist ihr erzeugtes Abbild und nie Ausgangspunkt einer Aenderung. Die Regeln dazu stehen in `CONTRIBUTING.md`.
- CLI: **nicht implementiert**. Produktvision haelt an einem CLI als CI/CD-Convenience-Layer fest -- ein store-first-Neuschnitt, wenn der CI-Bedarf konkret wird.
- Keine Datei-Pipeline: `arknet-core`, `arknet-projection` und die datei-basierten `arknet_*`-MCP-Tools existieren nicht mehr. Store-first (die BC-Tools) ist der einzige Modell-Lebenszyklus. Generierende Ausgabepfade: das self-contained `store-report.html` (`store_overview`) sowie, seit issue #415, `docs/adr-export/` -- ein reproduzierbarer, ins Repository committeter Store-Export (`.trig`-Volldump + Report), manuell erzeugt via `scripts/export-store-docs.sh`; siehe `docs/adr-export/README.md`.
- MCP-Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Projekte der Maschine (Streamable HTTP, `127.0.0.1:47331`), kein Claude-Code-Subprozess pro Session -- Grund: mehrere Sessions/Worktrees desselben Projekts teilen einen Store und kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock.
Welches Projekt ein Aufruf trifft, entscheidet der Anker, den der Client pro Aufruf mitschickt (`.mcp.json`-Header `X-Arknet-Project-Anchor: ${PWD}`, alternativ der optionale `projectAnchor`-Parameter jedes Tools) und den der Server ausschliesslich nachschlaegt -- darum genuegt ein Port fuer alle Projekte.
Details/Start: `arknet-mcp/CLAUDE.md`, `README.md`.
- Repo-Schnitt: `kogn-io/arknet` (dieses Repo, der Service) und `kogn-io/arknet-plugin` (das Claude Code Plugin) sind getrennte Repositories mit unabhaengigen Release-Zyklen/Versionsachsen.

## Tech-Stack

- Java 25+ (`release 25`; io.kogn.rdf ist Java-25-gebaut), Maven Multi-Module
- Bauen/Testen: shell-`mvn` (default JDK 25). jdt-mcp (Eclipse JDT als MCP-Server) versteht Java 25 und
  taugt fuer Navigation (`jdt_find_type`/`jdt_find_references`/`jdt_find_implementations`/`jdt_find_callers`),
  Diagnose (`jdt_get_compilation_errors`, nach externen Aenderungen erst `jdt_refresh_project`) und
  `jdt_organize_imports`; `jdt_find_references` auf einen Kernel-Typ liefert tausende Treffer ohne Limit,
  also Methoden-/Feld-Ebene abfragen. `jdt_run_tests` scheitert mit dem JUnit 6 des Projekts (Eclipse-Runner
  findet `Testable` nicht) -- Tests laufen ueber `mvn`. Eclipse sortiert Imports anders als Spotless.
- Lokaler Vollbuild: `mvn -T 1C clean install` -- Reaktor parallel je Kern, gemessen ca. 20-25%
  schneller als seriell, aber begrenzt durch eine tiefe Modulkette: `arknet-mcp` haengt von fast
  allen anderen Modulen ab und dominiert mit >2 Min allein den kritischen Pfad, egal wie parallel
  der Rest laeuft. `*RealStoreConcurrencyTest` u.a. nutzen durchweg `@TempDir`, kein
  Verzeichnis-Lock zwischen Modul-Forks -- sie konkurrieren allein um CPU. Gilt nur lokal, nicht
  fuer CI (`test.yml` bleibt bei seriellem `mvn -B verify` -- verschraenkte Logs waeren dort
  teurer als die gesparte Zeit).
- Test-Zeitgrenze: eine einzige projektweite (`junit.jupiter.execution.timeout.default` in den
  Surefire-`configurationParameters` der Root-POM), kein `@Timeout` je Testklasse. Sie faengt
  einen Hang ab (die Racer parken auf `CyclicBarrier`/`CountDownLatch`, ein Regress wuerde den
  Build stehen lassen statt ihn rot zu machen) und ist bewusst weit ueber jeder normalen
  Testlaufzeit: handgesetzte Budgets wirken unter Parallellast als Performance-Gate, das
  `-T 1C` zufaellig reisst.
- `clean` ist kein Reflex: nur nach Rebase/modulübergreifendem Umbau noetig (stale .class-Referenzen
  auf umbenannte/entfernte Typen), nicht nach gewoehnlicher Textaenderung -- `mvn install` reicht.
  Ausnahme, solange jdt-mcp verbunden ist: sein Workspace-Import gibt `src/test/java` keinen eigenen
  Output-Ordner, Eclipse schreibt die Test-Klassen aller Module nach `target/classes`, und ein `mvn install`
  ohne `clean` packt sie ins Jar -- mit verbundenem jdt-mcp also immer `clean`.
- RDF4J 6.x (Triple Store + SHACL Sail)
- AsciidoctorJ + asciidoctor-diagram (AsciiDoc → HTML/PDF)
- PlantUML (Diagramme)
- Mustache (Templates fuer AsciiDoc-Generierung)
- Spring AI 2.0 (Tech-Linie fuer den MCP-Layer -- `@McpTool`)
- kognio-rdf (`io.kogn.rdf`, embeddable RDF-Substrat hinter Out-Ports; OSS github.com/kogn-io/rdf-core)
- Turtle (.ttl) als Primaerformat

## Maven-Module

Je Modul liegt die Detail-Doku (Klassen, Ports, Invarianten, ADR-Bezuege) in
einer eigenen `CLAUDE.md` im Modulverzeichnis -- sie laedt, sobald dort gearbeitet
wird, und ist die massgebliche Beschreibung des Moduls. Hier nur die Landkarte.
Wie die Module zu Komponenten und Bounded Contexts gehoeren (Bausteinsicht), steht in
`docs/building-block-view.md`.

- **arknet-ontology**: nur .ttl-Ressourcen (Ontologie-Module, Shapes). `arknet-ontology/CLAUDE.md`
- **arknet-mcp**: MCP-Server (geteilter lokaler Daemon, `127.0.0.1:47331`) + Composition Root, verdrahtet alle sieben BC-Hexagons, die Anker-Aufloesung, den generischen Store-Lesepfad und den Pruefpfad `store_check`. `arknet-mcp/CLAUDE.md`
- **arknet-shared-kernel**: DDD Shared Kernel -- ProjectId, ProjectResolver-Port, ResourceId, DisplayLocale/LocalizedLiteral. `arknet-shared-kernel/CLAUDE.md`
- **arknet-persistence-support**: technischer Support der kognio-rdf-Out-Adapter -- SHACL-Write-Gate, WriteFunnel (PROV-O-Revision + Head-Pointer je Write), SparqlTerms, die `Ark*Vocabulary`-Konstanten. `arknet-persistence-support/CLAUDE.md`
- **arknet-persistence-test-support**: Test-Support derselben Out-Adapter -- die Guarded*-Dekoratoren fuer die `*RealStoreConcurrencyTest`; main-Scope-Artefakt, im test-Scope gezogen. `arknet-persistence-test-support/CLAUDE.md`
- **arknet-architecture-tests**: Invarianten, die der Modulschnitt nicht erzwingen kann -- ArchUnit-Regeln und die Abgleiche Vokabular-Konstanten/Loeschschutz-Listen/Stale-Translation-Schluessel/Ontologie-Versionen gegen die ausgelieferten Ontologien und Shapes. `arknet-architecture-tests/CLAUDE.md`
- **arknet-requirements**: BC 1 -- Requirement-Lifecycle (`req_*`) plus Constraint als zweiter Ressourcentyp (`constraint_*`), `usesTerm`-Kante ins Glossar. `arknet-requirements/CLAUDE.md`
- **arknet-ubiquitous-language**: BC 2 -- SKOS-Glossar (`term_*`). `arknet-ubiquitous-language/CLAUDE.md`
- **arknet-use-cases**: BC 3 -- Cockburn-Use-Cases (`uc_*`), Rollen per `ROLE-N`-Code aus dem Actor-Register, Kanten zu Glossar und Constraints. `arknet-use-cases/CLAUDE.md`
- **arknet-bounded-context**: BC 4 -- BoundedContext-Lifecycle (`bc_*`), ContextRelationship als eigene Ressource. `arknet-bounded-context/CLAUDE.md`
- **arknet-project**: BC 5 -- die Projekt-Registry (`project_*`), bildet den Anker eines Aufrufs auf das Projekt ab; einziger nicht projekt-scoped BC (System-Dataset); fuehrt Standardsprache und Sprachsatz des Projekts. `arknet-project/CLAUDE.md`
- **arknet-adr**: BC 6 -- Architecture-Decision-Record-Lifecycle (`adr_*`, `adr_check`), Kanten zu Requirements, Bounded Contexts und Glossar, `supersededBy`/`relatedTo` selbstbezueglich; arknets eigene ADRs sind Records dieses Hexagons. `arknet-adr/CLAUDE.md`
- **arknet-actor**: BC 7 -- Actor-Lifecycle (`actor_*`, `ACTOR-N`) und Role als zweiter Ressourcentyp (`role_*`, `ROLE-N`, `filledBy`). `arknet-actor/CLAUDE.md`

## Ontologie-Namespaces

- **Basis:** `https://w3id.org/arknet/`
- `https://w3id.org/arknet/core#` (Prefix: `arknet:`) — generisches Utility-Vokabular (name, description, ...), wiederverwendbar in jedem Modul
- `https://w3id.org/arknet/ddd#` (Prefix: `arkddd:`) — BoundedContext, Domain, Subdomain, ContextRelationship, RelationshipType (Live, `arknet-ddd.ttl`, von arknet-bounded-context genutzt); ContextMap sowie das taktische DDD (Aggregate, Entity, ValueObject, Command, DomainEvent, ...) bleiben geparkt (`parked/arknet-ddd_parked.ttl`, kein BC), teilen sich aber den Namespace
- `https://w3id.org/arknet/process#` (Prefix: `arkproc:`) — Actor (Unterklasse von `prov:Agent`)/HumanActor/SystemActor/LegalActor/GroupActor (Live, `arknet-actor.ttl`, von arknet-actor als eigenstaendige Ressource geschrieben, seit Issue #336 nicht mehr von arknet-ubiquitous-language), dazu seit ADR-37 Role/`filledBy` als zweiter, eigenstaendiger Ressourcentyp desselben Moduls (Actor bleibt der Traeger, Role die optionale, mehrwertig besetzbare Funktion) -- Role wird von arknet-use-cases zur Aufloesung von `primaryRole`/`supportingRole` per `ROLE-N`-Code gelesen; Process, Step, StateTransition, BusinessRule, Outcome bleiben geparkt (`parked/arknet-process.ttl`, kein BC)
- `https://w3id.org/arknet/requirements#` (Prefix: `arkreq:`) — Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release (OSLC-RM-aligned, doap:Version)
- `https://w3id.org/arknet/architecture#` (Prefix: `arkarch:`) — ArchitectureDecisionRecord samt Textfeldern, Consequence/ConsideredOption als eigene positionierte Ressourcen (`consequenceType`/`optionOutcome`), Relationen (`supersededBy` -- geschrieben auf dem abgeloesten Record, an eine Bi-Implikation mit dem `Superseded`-Status gekoppelt; `supersedes` als Alt-Schreibform vor Issue #357, nur noch lesend gepflegt; `relatedTo` als `owl:SymmetricProperty` -- geschrieben wird auch hier nur eine Richtung, gelesen wird eine zusammengefuehrte Liste; `addressesRequirement`, `affectsContext`, `usesTerm` -- kogn-io/arknet#393, eigene Property statt Erweiterung der geteilten `arkreq:usesTerm`-Domain) und den fuenf ADRStatus-Individuen (Live, `arknet-architecture.ttl`, von arknet-adr genutzt); die uebrige ISO-42010-Architekturbeschreibung (Architecture, ArchitectureDescription, Stakeholder, Concern, Viewpoint, View) bleibt geparkt (`parked/arknet-architecture_parked.ttl`, kein BC), teilt sich aber den Namespace
- `https://w3id.org/arknet/tech#` (Prefix: `arktech:`) — Service, Container, API, Database, MessageBroker
- `https://w3id.org/arknet/privacy#` (Prefix: `arkpriv:`) — DataCategory, LegalBasis, ProcessingPurpose
- `https://w3id.org/arknet/provenance#` (Prefix: `arkprov:`) — Revision (PROV-O-basiert), head (Head-Pointer = Concurrency-Token je Ressource)
- `https://w3id.org/arknet/project#` (Prefix: `arkprj:`) — Project (registrierte Store-Identitaet), Anchor + AnchorType (`PathAnchor`/`UrlAnchor`/`UuidAnchor`), defaultLanguage (einwertiger Rueckfall) und maintainedLanguage (mehrwertige Zusage); Praefix bewusst `arkprj` statt `arkproj`, um die Verwechslung mit `arkproc:` in SPARQL-Queries auszuschliessen
- Namensgebungskonvention (ein Namespace-Name behauptet nicht mehr, als der Namespace traegt): `arknet-ontology/CLAUDE.md`

## Ubiquitous Language

Die Begriffsdefinitionen des Projekts (Metamodell, Architekturmodell, Projekt, Anker, ...) leben
als Glossar im arknet-Store selbst, nicht hier -- Zweitpflege in Markdown wuerde vom Store
abdriften. Abfragbar ueber `term_list`/`term_get` (arknet-ubiquitous-language BC).

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
Service releasen unabhaengig voneinander. Die Root-`.mcp.json` dieses
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
`description`/`rationale`/AcceptanceCriterion-`text` (`req_add`/`req_update`),
UseCase-`title`/`goal`/`scope`/`trigger`/`precondition`/`postcondition`/
Step-`text`/Extension-`text` (`uc_add`/`uc_update`), Constraint-`title`/
`constraintStatement` (`constraint_add`/`constraint_update`), Role-`name`/
`description` (`role_add`/`role_update`), BoundedContext-`name`/
`domainVision` (`bc_add`/`bc_update`) sowie Actor-`name`/`description`
(`actor_add`/`actor_update`, kogn-io/arknet#520) sowie die
ADR-Felder `name`/`context`/`decision` samt Consequence- und
ConsideredOption-Texten (`adr_add`/`adr_update`) tragen jeweils
mehrere sprachgetaggte RDF-Literale je Ressource, ueber ein optionales
`language`-Argument beim Schreiben gesetzt und beim Lesen ueber die
`DisplayLocale`-Fallback-Kette aufgeloest. Ausgenommen von der Uebersetzung
ist das `prefLabel` eines Glossarbegriffs: es traegt unter jedem Sprachtag
dasselbe Wort, uebersetzt wird allein die Definition -- zwei Woerter fuer
denselben Begriff heben auf, wozu ein Glossar da ist. `term_update` erzwingt
das: ein abweichendes `label` unter explizitem `language` wird abgelehnt,
ein `label` ohne `language` benennt den Begriff dagegen unter allen bereits
vorhandenen Sprachtags gleichzeitig um. Arknets **eigenes**
Modell in diesem Store -- Glossar, Requirements, Constraints, Use Cases und
die eigenen ADRs -- wird in **beiden** Sprachen gefuehrt, Deutsch und Englisch
gleichrangig: eine inhaltliche Aenderung an einem mehrsprachigen Feld ist erst
vollstaendig, wenn beide Sprachen sie tragen (ein Schreibaufruf traegt genau
eine Sprache, also zwei Aufrufe je Aenderung). Das ist eine Projektregel fuer
diesen Bestand, keine Werkzeugregel -- andere Projekte im selben Store duerfen
einsprachig bleiben. Die Werkzeugseite dieses Umstands ist ein Signal, kein
Zwang: jedes `*_update`, das ein mehrsprachiges Feld schreibt, haengt an seine
Antwort, welche der vom Projekt gefuehrten Sprachen (`arkprj:maintainedLanguage`)
das geschriebene Feld noch traegt, ohne dass dieser Aufruf sie geschrieben haette
-- also moeglicherweise veraltet ist. Ein Feld, das die geschriebene Sprache
vorher nicht trug, wird uebersetzt, nicht korrigiert, und bekommt keinen
Hinweis; darum wird der Bestand vor dem Schreiben nachgeschlagen. Der
Mechanismus dahinter (`StaleTranslationHint`
+ `FieldLanguageLookup` im Shared Kernel, im Composition Root ueber den
generischen Store-Lesepfad bedient) ist einer fuer alle neun Tools; er blockt
nie und behauptet keine Revision je Sprachvariante -- der WriteFunnel fuehrt
Revisionen je Ressource, nicht je Literal. Das Gegenstueck dazu ist
`store_check LANGUAGE`: dort fehlt eine Sprache ganz, hier ist sie da, aber
alt. Quer dazu akzeptiert **jedes**
Prosa-Feld ein enges Markdown-Subset (`**fett**`, `*kursiv*`, `` `code` ``,
`- `-Listen, Absaetze an Leerzeilen); Links, Ueberschriften, Tabellen und HTML
bleiben bewusst Text, weil ein handgeschriebener Link den modellvalidierten
Bezug (`usesTerm` & Co.) und damit die Luecken-Erkennung umginge. Das
Store-Literal bleibt roh -- geparst wird beim Lesen (`ProseMarkdown` in
`arknet-mcp`), nicht beim Schreiben.

## Regel fuer diese Datei

`CLAUDE.md` wird mit ausgeliefert. Hier steht nur, was ein Fremder auch aus dem
Code ableiten koennte: Struktur, Konventionen, Invarianten, Bauanleitung.
**Nicht hierher gehoeren:** Repo-Sichtbarkeit und Zugangswege, interne
Infrastruktur (Hostnames, Tracker, Marketplaces), Editions-/Preis-/
Monetarisierungsfragen, Herkunft aus nicht-oeffentlichen Vorprojekten sowie
alles mit Status- oder Zeitbezug ("derzeit", "noch nicht gepusht"). Das gilt
fuer die Modul-`CLAUDE.md` genauso.
