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
Arknets eigene Architekturentscheidungen stehen im arknet-Store (`adr_*`-Tools des Moduls arknet-adr) und als erzeugtes Abbild unter `docs/adr-export/`; die Regeln dazu stehen in `CONTRIBUTING.md`.
- CLI: **nicht implementiert**. Produktvision haelt an einem CLI als CI/CD-Convenience-Layer fest -- ein store-first-Neuschnitt, wenn der CI-Bedarf konkret wird.
- Keine Datei-Pipeline: `arknet-core`, `arknet-projection` und die datei-basierten `arknet_*`-MCP-Tools existieren nicht mehr. Store-first (die BC-Tools) ist der einzige Modell-Lebenszyklus. Generierende Ausgabepfade: das self-contained `store-report.html` (`store_overview`) sowie, seit issue #415, `docs/adr-export/` -- ein reproduzierbarer, ins Repository committeter Store-Export (`.trig`-Volldump + Report), manuell erzeugt via `scripts/export-store-docs.sh`; siehe `docs/adr-export/README.md`.
- MCP-Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Projekte der Maschine (Streamable HTTP, `127.0.0.1:47331`), kein Claude-Code-Subprozess pro Session -- Grund: mehrere Sessions/Worktrees desselben Projekts teilen einen Store und kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock.
Welches Projekt ein Aufruf trifft, entscheidet der Anker, den der Client pro Aufruf mitschickt (`.mcp.json`-Header `X-Arknet-Project-Anchor: ${PWD}`, alternativ der optionale `projectAnchor`-Parameter jedes Tools) und den der Server ausschliesslich nachschlaegt -- darum genuegt ein Port fuer alle Projekte.
Details/Start: `arknet-mcp/CLAUDE.md`, `README.md`.
- Repo-Schnitt: `kogn-io/arknet` (dieses Repo, der Service) und `kogn-io/arknet-plugin` (das Claude Code Plugin) sind getrennte Repositories mit unabhaengigen Release-Zyklen/Versionsachsen.

## Tech-Stack

- Java 25+ (`release 25`; io.kogn.rdf ist Java-25-gebaut), Maven Multi-Module
- Bauen/Testen: shell-`mvn` (default JDK 25); JDT-MCP kann `--release 25` (noch) nicht
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
- **arknet-mcp**: MCP-Server (Streamable HTTP, EIN geteilter lokaler Daemon fuer alle Projekte auf `127.0.0.1:47331`, admin-gestartet -- kein Claude-Code-Subprozess; Projekt pro Aufruf ueber den Anker aus dem Header) + Composition Root, verdrahtet alle sieben BC-Hexagons + geteilter DatasetLifecycle + der Anker-Aufloesung ueber die Projekt-Registry + generischer Store-Lesepfad (der Agent-Digest bleibt generisch, der HTML-Report wird pro BC aus deren Lese-In-Ports zusammengesetzt und faellt fuer alles Uebrige auf die generische Rohsicht zurueck) + der generische Pruefpfad `store_check` (ein Tool mit `checks`-Selektor statt eines Tools je Regel; derzeit allein der Sprachluecken-Check).
Details: `arknet-mcp/CLAUDE.md`
- **arknet-shared-kernel**: DDD Shared Kernel -- ProjectId (inkl. der reservierten System-Dataset-Invariante), ProjectResolver-Port (Per-Aufruf-Aufloesung ueber den Anker), ResourceId, DisplayLocale/LocalizedLiteral. Details: `arknet-shared-kernel/CLAUDE.md`
- **arknet-persistence-support**: technischer Support der kognio-rdf-Out-Adapter -- geteiltes SHACL-Write-Gate, geteilter Schreibtrichter WriteFunnel (schreibt je Write atomar eine PROV-O-Revision + Head-Pointer), SparqlTerms, UnresolvedReferenceException, die Vokabular-Konstanten ArkprovVocabulary/ArkreqVocabulary/ArkdddVocabulary/ArkprjVocabulary/ArkarchVocabulary/ArkprocVocabulary sowie ExportMetadataVocabulary (die IRIs des Export-Metadaten-Graphen -- als einziges Vokabular dieses Moduls nie in ein Dataset geschrieben, nur in den serialisierten Dump).
Details: `arknet-persistence-support/CLAUDE.md`
- **arknet-persistence-test-support**: Test-Support derselben kognio-rdf-Out-Adapter -- die geteilten Dekoratoren GuardedLifecycle/GuardedHandle/GuardSyncTx fuer DatasetLifecycle/DatasetHandle/DatasetTx, mit denen die `*RealStoreConcurrencyTest` der BCs eine gewaehlte Verschraenkung zweier Schreiber gegen den echten On-Disk-Store festnageln; gewoehnliches main-Scope-Artefakt (kein `test-jar`-Classifier), von den Konsumenten im test-Scope gezogen.
Details: `arknet-persistence-test-support/CLAUDE.md`
- **arknet-architecture-tests**: Invarianten, die der Modulschnitt nicht erzwingen kann -- ArchUnit-Dependency-Regeln, der beidseitige Abgleich von `ArkprovVocabulary`/`ArkprjVocabulary`/`ArkarchVocabulary`/`ArkdddVocabulary`/`ArkprocVocabulary` gegen die ausgelieferte Provenance-, Projekt-, Architektur-, DDD- bzw. Actor-Ontologie plus der Abgleich der Loeschschutz-Listen von `term_delete`/`actor_delete` gegen jede Ontologie-Property, die auf einen Term bzw. Actor zeigt, und der Abgleich des Ontologie-Versions-Scans von `arknet-mcp`s `OntologyVersions` gegen einen echten Parse derselben Dateien. Details: `arknet-architecture-tests/CLAUDE.md`
- **arknet-requirements**: erste hexagonale BC -- Requirement-Lifecycle (`req_*`-Tools), usesTerm-Kante ins Glossar (`arkreq:usesTerm`, seit Issue #329 auch von UseCase aus setzbar, die Kante bleibt aber requirements-BC-eigen), opake Identitaet, acceptanceCriterion.
Traegt zusaetzlich Constraint als zweiten Ressourcentyp desselben Hexagons (`constraint_add`/`constraint_get`/`constraint_list`/`constraint_update`, `req_link_constraint` fuer die `oslc_rm:constrainedBy`-Kante) -- technische/geschaeftliche/regulatorische Randbedingungen, TCON-/BCON-/RCON-Codes; Typ und Code stehen mit der Anlage fest, Titel und Statement sind korrigierbar und mehrsprachig.
Details: `arknet-requirements/CLAUDE.md`
- **arknet-ubiquitous-language**: zweite hexagonale BC -- SKOS-Glossar (`term_*`-Tools), opake Identitaet. Traegt seit Issue #336 keine Actor-Facette mehr; Akteure leben ausschliesslich im Actor-Register (arknet-actor). Details: `arknet-ubiquitous-language/CLAUDE.md`
- **arknet-use-cases**: dritte hexagonale BC -- Cockburn-Use-Cases (`uc_*`-Tools) mit opaken Step-VOs, Actor-/Requirement-Referenzen.
Traegt seit Issue #329 zusaetzlich `usesTerm`-Kanten ins Glossar (`uc_link_term`) und `constrainedBy`-Kanten zu Constraints der requirements-BC (`uc_link_constraint`), damit ein rein use-case-gefuehrtes Projekt (Requirement optional, Issue #327) strukturell vernetzt bleibt.
Details: `arknet-use-cases/CLAUDE.md`
- **arknet-bounded-context**: vierte hexagonale BC -- BoundedContext-Lifecycle (`bc_*`-Tools), ubiquitousLanguageTerm-Kante ins Glossar (BC->Term), `arkddd:ContextRelationship` als eigene Ressource fuer die gerichtete Context-Mapping-Kante zwischen zwei Bounded Contexts (`bc_link_context`), opake Identitaet. Details: `arknet-bounded-context/CLAUDE.md`
- **arknet-project**: fuenfte hexagonale BC -- die Projekt-Registry (`project_*`-Tools), die einen vom Client gesendeten, opaken und typisierten Anker auf das Projekt abbildet, dessen Dataset die Modelldaten haelt.
Verwaltet Identitaet statt Modell und ist als einziger BC nicht projekt-scoped: seine Registry wohnt im reservierten System-Dataset.
Sie ist die Aufloesungsquelle, auf die jeder Tool-Aufruf der sechs Modell-BCs geroutet wird.
Ein Projekt fuehrt neben der einwertigen Standardsprache (`arkprj:defaultLanguage`, der Rueckfall fuer einen Aufruf ohne eigenes `language`) einen mehrwertigen Sprachsatz (`arkprj:maintainedLanguage`, ueber `project_add`/`project_update` als `languages` gesetzt) -- die Zusage, in welchen Sprachen es sein Modell fuehrt, und damit der Sollzustand, gegen den `store_check` Unvollstaendigkeit ueberhaupt benennen kann; ist der Satz nicht leer, muss die Standardsprache Element von ihm sein.
Details: `arknet-project/CLAUDE.md`
- **arknet-adr**: sechste hexagonale BC -- Architecture-Decision-Record-Lifecycle (`adr_add`/`adr_list`/`adr_get`/`adr_update`/`adr_set_status`/`adr_supersede`/`adr_unsupersede`/`adr_delete`, `adr_unsupersede` seit kogn-io/arknet#354) plus `adr_check`, die lesende, nicht-blockierende Konsistenz- und Qualitaetspruefung ueber den ganzen Korpus (kogn-io/arknet#387: Fakten und Musterverdacht getrennt, und die Regeln, die sie nicht pruefen kann, in ihrer eigenen Ausgabe benannt), `addressesRequirement`-Kante zu den Requirements, `affectsContext`-Kante zu den Bounded Contexts, `usesTerm`-Kante zu den Glossarbegriffen (kogn-io/arknet#393, eigene Property statt Erweiterung der geteilten `arkreq:usesTerm`-Domain, weil eine ADR im eigenen `arkarch`-Namespace lebt), zwei selbstbezuegliche Kanten (`supersededBy` als Lifecycle-Akt mit eigenem Tool -- ein echter, an die Kante gekoppelter `SUPERSEDED`-Status, geschrieben auf dem abgeloesten Record, mit `adr_unsupersede` als eigenem Rueckweg-Tool fuer eine vertippte Abloesung --, `relatedTo` als gleichrangiger Querverweis, ueber `adr_add`/`adr_update` setzbar) -- von beiden wird nur die Vorwaertsrichtung als Tripel geschrieben, opake Identitaet.
`Consequence`/`ConsideredOption` sind eigene, positionierte Ressourcen statt flacher Literale, mit je einem Klassifikationsfeld (`consequenceType` POSITIVE/NEGATIVE/NEUTRAL bzw. `optionOutcome` CHOSEN/REJECTED); `name`/`context`/`decision` sowie die Consequence-/ConsideredOption-Texte sind mehrsprachig.
Korrektur ist gestaffelt: die Textfelder sind nur solange `PROPOSED` aenderbar (die Ablehnung nennt je Status den gangbaren Weg -- `adr_supersede` nur ab `ACCEPTED`, dem einzigen Status, den diese Kante annimmt, sonst eine eigenstaendige neue Entscheidung), mit einer feingranularen Uebersetzungs-Ausnahme -- eine Sprache, die ein Feld bzw. eine Position noch nie trug, ist in jedem Status schreibbar, die Klassifikationsfelder sind davon ausgenommen. Eine Consequence bzw. ConsideredOption laesst sich per Position auch wieder entfernen (`removeConsequencePositions`/`removeConsideredOptionPositions` an `adr_update`, die nachfolgenden ruecken auf) -- ebenfalls nur solange `PROPOSED`, ohne Uebersetzungs-Ausnahme. Die vier Referenzlisten (`addressesRequirement`, `affectsContext`, `usesTerm`, `relatedTo`) bleiben dagegen in jedem Status korrigierbar.
Geloescht werden kann ebenfalls nur ein `PROPOSED`-Record -- `adr_delete` macht ein versehentliches `adr_add` rueckgaengig und ist kein Lifecycle-Schritt; `REJECTED` ("erwogen und verworfen") ist eine dokumentierte Entscheidung und darum genauso wenig loeschbar wie `ACCEPTED`.
Abgelehnt wird ausserdem, solange ein anderer Record ihn ueber `supersededBy` als seinen Nachfolger benennt (aufloesbar mit `adr_unsupersede` auf diesem anderen Record) oder ueber `relatedTo` auf ihn zeigt; der Code eines geloeschten Records bleibt vergeben (er ueberlebt als `dcterms:identifier` an der getombstoneten Revision), damit `ADR-7` nie zwei Entscheidungen benennt.
Arknets eigene Architekturentscheidungen sind Records dieses Hexagons im arknet-eigenen Store; `docs/adr-export/` ist ihr erzeugtes Abbild.
Details: `arknet-adr/CLAUDE.md`
- **arknet-actor**: siebte hexagonale BC -- Actor-Lifecycle (`actor_*`-Tools), `ACTOR-N`-Codes aus einem Zaehler fuer alle vier Typen (`HUMAN`/`SYSTEM`/`LEGAL`/`GROUP`; Typ und Code stehen mit der Anlage fest, Name und Beschreibung sind korrigierbar), opake Identitaet.
Macht `arkproc:Actor` zu einer eigenstaendigen Ressource statt zu einer Facette am Glossarbegriff: ein Akteur braucht weder Definition noch `TERM-N`-Code, darf aber zusaetzlich Glossarbegriff sein.
Name und Beschreibung sind bewusst ungetaggte Literale ohne Mehrsprachigkeits-Mechanismus, und der Hexagon traegt keine Cross-BC-Kante. Seit Issue #336 loest arknet-use-cases Akteurnamen gegen dieses Register auf (`ActorLookup`/`ResolveActors`) statt gegen die entfernte ubiquitous-language-Actor-Facette.
Details: `arknet-actor/CLAUDE.md`

## Ontologie-Namespaces

- **Basis:** `https://w3id.org/arknet/`
- `https://w3id.org/arknet/core#` (Prefix: `arknet:`) — generisches Utility-Vokabular (name, description, ...), wiederverwendbar in jedem Modul
- `https://w3id.org/arknet/ddd#` (Prefix: `arkddd:`) — BoundedContext, Domain, Subdomain, ContextRelationship, RelationshipType (Live, `arknet-ddd.ttl`, von arknet-bounded-context genutzt); ContextMap sowie das taktische DDD (Aggregate, Entity, ValueObject, Command, DomainEvent, ...) bleiben geparkt (`parked/arknet-ddd_parked.ttl`, kein BC), teilen sich aber den Namespace
- `https://w3id.org/arknet/process#` (Prefix: `arkproc:`) — Actor (Unterklasse von `prov:Agent`)/HumanActor/SystemActor/LegalActor/GroupActor/actorRole (Live, `arknet-actor.ttl`, von arknet-actor als eigenstaendige Ressource geschrieben und von arknet-use-cases zur Aufloesung von `primaryActor`/`supportingActor` gelesen, seit Issue #336 nicht mehr von arknet-ubiquitous-language); Process, Step, StateTransition, BusinessRule, Outcome bleiben geparkt (`parked/arknet-process.ttl`, kein BC)
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
`description`/AcceptanceCriterion-`text` (`req_add`/`req_update`),
UseCase-`title`/`goal`/`scope`/`trigger`/`precondition`/`postcondition`/
Step-`text`/Extension-`text` (`uc_add`/`uc_update`), Constraint-`title`/
`constraintStatement` (`constraint_add`/`constraint_update`) sowie die
ADR-Felder `name`/`context`/`decision` samt Consequence- und
ConsideredOption-Texten (`adr_add`/`adr_update`) tragen jeweils
mehrere sprachgetaggte RDF-Literale je Ressource, ueber ein optionales
`language`-Argument beim Schreiben gesetzt und beim Lesen ueber die
`DisplayLocale`-Fallback-Kette aufgeloest. Ausgenommen von der Uebersetzung
ist das `prefLabel` eines Glossarbegriffs: es traegt unter jedem Sprachtag
dasselbe Wort, uebersetzt wird allein die Definition -- zwei Woerter fuer
denselben Begriff heben auf, wozu ein Glossar da ist. Arknets **eigenes**
Modell in diesem Store -- Glossar, Requirements, Constraints, Use Cases und
die eigenen ADRs -- wird in **beiden** Sprachen gefuehrt, Deutsch und Englisch
gleichrangig: eine inhaltliche Aenderung an einem mehrsprachigen Feld ist erst
vollstaendig, wenn beide Sprachen sie tragen (ein Schreibaufruf traegt genau
eine Sprache, also zwei Aufrufe je Aenderung). Das ist eine Projektregel fuer
diesen Bestand, keine Werkzeugregel -- andere Projekte im selben Store duerfen
einsprachig bleiben. Andere Freitext-Felder
(z.B. BoundedContext-`name`/`description`) bleiben einfache, ungetaggte
Literale ohne diesen Mechanismus. Quer dazu akzeptiert **jedes**
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
