# ADR-001: Editions-Modell und austauschbarer Store

## Status

Akzeptiert (2026-07-13)

## Kontext

arknet verwaltet Architekturmodelle (DDD, Requirements, ...) als RDF und exponiert sie
MCP-first fuer KI-Agenten. Zwei Nutzungsszenarien stehen im Raum:

1. Ein einzelner Architekt arbeitet lokal an einem oder mehreren Modellen.
2. Ein Team arbeitet gemeinsam an einem Modell.

Zugleich soll arknet schlank und lokal bleiben (stdio-MCP, embeddable), aber der Weg zum
Team-Szenario darf nicht verbaut werden. Das Schwesterprojekt kognio-memory loest
Team / Multi-Project / Auth bereits vollstaendig (Repo-per-Project, ApiKey, Actor;
siehe dessen ADR-006 zur Trennung der Identity-Konzepte).

## Entscheidung

### 1. arknet ist ein lokaler Single-User-Client

arknet laeuft lokal (stdio-MCP + CLI), single-user. `arknet-core` und die
Komponenten-Cores kennen KEINEN Auth-, Tenancy- oder Team-Begriff. Nutzer-Identitaet und
Zugriffskontrolle sind kein Belang von arknet.

### 2. Der Store ist hinter einem domaennahen Out-Port austauschbar

Persistenz laeuft ausschliesslich ueber domaennahe Out-Ports (z.B.
`RequirementRepository` mit `Requirement` rein/raus -- keine Triples, keine RDF-Typen im
Port). Zwei Adapter erfuellen denselben Port:

- **Adapter A -- lokal (Community Edition):** `requirements-adapter-kogniordf` ueber
  kognio-rdf (embeddable RDF-Substrat). Single-User, lokales Dataset.
- **Adapter B -- remote (Closed Edition, spaeter):** `requirements-adapter-kogniomemory`,
  ein MCP-Client gegen kognio-memory. Dieses Backend traegt Team / Multi-Project / Auth
  selbst.

arknet ist damit zugleich **MCP-Server** (fuer den treibenden Agenten) und **MCP-Client**
(fuer kognio-memory als Store).

### 3. Editions-Modell: Open-Core

- **Community Edition = OSS:** Komponenten-Cores + Adapter A + Spring-AI-MCP-Layer. Dient
  als Spring-AI-+-RDF-Showcase und Community-Building. Konkrete OSS-Lizenz noch offen.
- **Closed Edition = proprietaer:** Adapter B + Team-Distribution.

Die Community-Distribution enthaelt nur Adapter A; die Composition Root waehlt den Adapter
zur Build-/Distributionszeit. Adapter B lebt in einem separaten, nicht-ausgelieferten
Modul/Repo.

### 4. Invarianten, die den Weg offenhalten (ab sofort, auch im Single-User-Bau)

1. Out-Ports bleiben domaennah und backend-agnostisch.
2. **WorkspaceId** laeuft durch In-Ports und Out-Port als Routing-Key. Lokal (Adapter A)
   ist es ein impliziter Default-Workspace; remote (Adapter B) der Project-Selektor.
3. Komponenten-Cores bleiben frei von Auth / Tenancy / kognio-memory.
4. Adapter B in separatem, nicht-ausgeliefertem Modul/Repo.

### 5. Security-Richtung fuer Adapter B (deferred)

Adapter B authentifiziert als MCP-Client per OAuth 2.0 nach dem Muster von
`org.springaicommunity:mcp-client-security` (Spring -- passt zur Spring-AI-Tech-Linie).
Voraussetzung: kognio-memory spricht serverseitig OAuth2 (heute ApiKey) -- wird gebaut,
wenn Adapter B ansteht. Bis dahin kein Auth-Code in arknet.

## Konsequenzen

### Positiv

- Team-Faehigkeit, ohne arknet mit Auth/Tenancy zu belasten -- sie ist ein Adapter-Austausch.
- Klare Editions-/Monetarisierungsgrenze entlang der Port-Grenze.
- Single-User bleibt schlank; nichts Spekulatives im Core.

### Negativ / bewusst deferred (YAGNI)

- Kein Workspace-CRUD / keine Workspace-Persistenz (Adapter A hat genau einen
  Default-Workspace).
- Kein Actor/Provenance-Feld am `Requirement` (generatedBy = Agent additiv spaeter,
  PROV-konform analog kognio-memory ADR-006).
- Team-Schreibpfad braucht spaeter transaktionale SHACL-Validierung
  (kogn-io/rdf-core#2); lokal reicht die standalone Variante (#3).

## Alternativen verworfen

- **arknet verwaltet Multi-User/Team selbst.** Dupliziert kognio-memory, blaeht den lokalen
  Client auf, bricht "schlank & lokal". Verworfen.
- **Kein Workspace-Parameter, solange single-user.** Spart heute eine Zeile, erzwingt
  spaeter eine Signatur-Migration an fertigem Code (Adapter B braucht den Routing-Key).
  Verworfen.
- **Triple-/GraphStore-Out-Port statt domaennahem Repository.** Wuerde Adapter B an arknets
  RDF-Interna binden und die Austauschbarkeit zerstoeren. Verworfen.

## Offene Punkte

- **WorkspaceId-Herkunft im Single-User-Betrieb.** Aktuell setzt der MCP-Adapter
  hardcodiert `WorkspaceId.DEFAULT` -- ein Platzhalter, der die Signatur offenhaelt, aber
  kein Modell-Management ist (effektiv genau ein lokaler Workspace). Zielbild: die
  WorkspaceId kommt **von der Composition Root beim MCP-Server-Start** (Startup-Arg/Env
  oder Arbeitsverzeichnis; ein stdio-Prozess = ein Modell) als session-scoped Wert, der in
  die Tools injiziert wird -- nicht aus einer Domain-Konstante. Umsetzung beim
  arknet-mcp-Umbau (#27).
- **mcp-core-Doppelversion** (1.1.0 raw / 2.0.0 Spring AI) bis arknet-mcp migriert ist -- #27.

## Referenzen

- kognio-memory ADR-006 (drei orthogonale Identity-Konzepte) -- Vorbild fuer die Trennung
  Modell-Achse (WorkspaceId) vs. User-Achse (Auth, im Backend).
- kogn-io/rdf-core#2 (transaktionale SHACL, deferred), #3 (standalone SHACL).
- Forgejo kogn-io/arknet #25 (schreibende MCP-Tools), #26 (requirements-Komponente MVP).
- Tech-Linie Spring AI 2.0 fuer den MCP-Layer (Kandidat fuer eigenen ADR-002).
