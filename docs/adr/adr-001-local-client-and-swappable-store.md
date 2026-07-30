# ADR-001: Lokaler Single-User-Client und austauschbarer Store

- Status: Proposed (2026-07-13)
- Verwandt: ADR-003, ADR-005, ADR-009 (MCP-Transport -- praezisiert, wie der
  lokale MCP-Server erreichbar ist, ohne die Single-User-Client-Entscheidung hier zu
  aendern), ADR-011 (loest die hier deferrierte Provenance ein), ADR-016 (loest die unten
  festgehaltene Herkunft der Store-Identitaet ab, benennt ihren Gegenstand vom Workspace zum
  Projekt um und loest das hier deferrierte Management dieser Identitaet ein)

## Kontext

arknet verwaltet Architekturmodelle (DDD, Requirements, ...) als RDF und exponiert sie
MCP-first fuer KI-Agenten. Zwei Nutzungsszenarien stehen im Raum: ein einzelner Architekt
arbeitet lokal an einem oder mehreren Modellen; oder ein Team arbeitet gemeinsam an einem
Modell. arknet soll dabei schlank und lokal bleiben (lokaler MCP-Server, embeddable Store), aber der Weg
zum Team-Szenario darf nicht verbaut werden. Fuer das Team-Szenario existiert ein separates remote
Backend, das Team / Multi-Project / Auth selbst traegt.

## Entscheidung

1. **arknet ist ein lokaler Single-User-Client** (lokaler MCP-Server + CLI; der konkrete
   MCP-Transport ist ADR-009s Entscheidung, nicht diese). `arknet-core` und die
   Komponenten-Cores kennen KEINEN Auth-, Tenancy- oder Team-Begriff; Nutzer-Identitaet und
   Zugriffskontrolle sind kein Belang von arknet.

2. **Der Store ist hinter einem domaennahen Out-Port austauschbar.** Persistenz laeuft
   ausschliesslich ueber domaennahe Out-Ports (z.B. `RequirementRepository` mit
   `Requirement` rein/raus -- keine Triples, keine RDF-Typen im Port). Zwei Adapter
   erfuellen denselben Port:
   - **Adapter A -- lokal:** `arknet-requirements-adapter-kogniordf` ueber kognio-rdf
     (embeddable RDF-Substrat). Single-User, lokales Dataset.
   - **Adapter B -- remote (spaeter):** ein MCP-Client gegen ein separates Remote-Backend
     (Modulname folgt bei Umsetzung). Dieses Backend traegt Team / Multi-Project / Auth selbst.

   arknet ist damit zugleich MCP-Server (fuer den treibenden Agenten) und MCP-Client (fuer
   das Remote-Backend als Store).

3. **Invarianten, die den Weg offenhalten** (ab sofort, auch im Single-User-Bau):
   Out-Ports bleiben domaennah und backend-agnostisch; eine **WorkspaceId** laeuft durch
   In- und Out-Ports als Routing-Key und selektiert lokal wie remote das Projekt/Modell
   (Adapter A bildet sie auf ein eigenes Dataset/Named-Graph im eingebetteten Substrat ab --
   eine Installation kann also mehrere Projekte fuehren); die Cores bleiben frei von
   Auth/Tenancy; Adapter B lebt in einem separaten, nicht-ausgelieferten Modul/Repo.

## Konsequenzen

**Positiv:** Team-Faehigkeit wird zum reinen Adapter-Austausch, ohne den Core mit
Auth/Tenancy zu belasten. Der Single-User-Bau bleibt schlank; nichts Spekulatives im Core.

**Negativ / bewusst deferred (YAGNI):** Kein Workspace-Management (CRUD/Listing) -- weiterhin
deferred, bis der Bedarf konkret ist (das Routing selbst traegt bereits mehrere Projekte).

Die zunaechst offen gelassene **Herkunft der WorkspaceId je Session ist mit PR #30 entschieden:**
eine MCP-Serverinstanz = genau ein Workspace, einmal beim Start aufgeloest ueber
`WorkspaceIdResolver` (explizites Property `arknet.workspace.id` verbatim -> geslugter
git-Toplevel-Verzeichnisname -> geslugter Arbeitsverzeichnis-Name -> `WorkspaceId.DEFAULT`).
Damit isoliert jedes Claude-/git-Projekt seine Daten ohne Konfigurationszwang, explizit
ueberschreibbar in der `.mcp.json`. Diese Herkunft ist inzwischen zweifach abgeloest: ADR-009
verlegt die Aufloesung vom Serverstart auf den einzelnen Aufruf, ADR-016 ersetzt die Ableitung
durch registrierte Anker, macht das Projekt zum Store-Gegenstand (der Workspace-Begriff entfaellt)
und loest damit zugleich das oben deferrierte Management dieser Identitaet ein. Kein Actor/Provenance-Feld am
`Requirement` (generatedBy = Agent additiv spaeter, PROV-konform). SHACL-Validierung auf
dem Schreibpfad ist auch lokal ein Ziel, nicht nur ein Team-Belang.
**Single-User ist nicht gleichbedeutend mit Single-Writer:** ein einzelner Nutzer treibt
ueblicherweise mehrere parallele Sessions/Agenten-Aufrufe gegen denselben lokalen
Adapter-A-Store -- das ist der Normalfall, kein Randfall, und gilt unabhaengig von Auth/
Tenancy. Nebenlaeufigkeitssicherheit fuer geteilten veraenderlichen Zustand (z.B. atomare
Vergabe menschenlesbarer Business-Codes, konfliktsichere Schreiboperationen) ist deshalb
bereits im Single-User-Bau notwendig, nicht erst mit Adapter B. Adapter B (ADR-003) addiert
zusaetzlich echte Multi-Tenant-Nebenlaeufigkeit (mehrere Nutzer/Teams) und verlangt dafuer
transaktionale SHACL-Validierung -- eine staerkere, aber andere Anforderung als die
Nebenlaeufigkeit innerhalb eines einzelnen Nutzers.

## Alternativen

- **arknet verwaltet Multi-User/Team selbst.** Dupliziert das remote Backend, blaeht den
  lokalen Client auf, bricht "schlank & lokal". Verworfen.
- **Kein Workspace-Parameter, solange single-user.** Spart heute eine Zeile, erzwingt
  spaeter eine Signatur-Migration an fertigem Code (Adapter B braucht den Routing-Key).
  Verworfen.
- **Triple-/GraphStore-Out-Port statt domaennahem Repository.** Wuerde Adapter B an arknets
  RDF-Interna binden und die Austauschbarkeit zerstoeren. Verworfen.
- **WorkspaceId-Herkunft nur explizit (`arknet.workspace.id` Pflicht).** Jedes Projekt muesste
  die Id in der `.mcp.json` setzen, um isoliert zu sein -- Isolation waere Opt-in statt Default.
  Verworfen zugunsten des git-Toplevel-Fallbacks (zero-config pro git-Projekt).
- **WorkspaceId-Herkunft nur aus dem Arbeitsverzeichnis (CWD).** Ohne git-Bezug weniger stabil
  (Umbenennen/Verschieben aendert die Id -> anderes Dataset) und ohne expliziten Override.
  Verworfen.
