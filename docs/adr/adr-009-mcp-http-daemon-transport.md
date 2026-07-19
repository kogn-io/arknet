# ADR-009: MCP-Transport -- ein geteilter HTTP-Daemon statt stdio-Subprozess pro Session

- Status: Proposed (2026-07-19)
- Verwandt: ADR-001 (lokaler Single-User-Client -- diese ADR aendert nur Transport und
  Betriebsmodell, nicht die lokale Client-Natur), ADR-004 (Spring-AI-2.0-Tech-Linie -- dieselbe
  Linie, nur der HTTP- statt der stdio-Transport-Baustein), ADR-003 (Adapter B/kognio-memory --
  explizit NICHT die Loesung hier, siehe Alternativen)

## Kontext

arknet-mcp lief bislang als stdio-Subprozess, den Claude Code pro Session spawnt (ADR-004). Seit
die WorkspaceId ueber den git-common-dir vereinheitlicht ist (#136), teilen sich Hauptcheckout
und alle Worktrees eines Projekts denselben Workspace und damit denselben lokalen RDF-Store.
Mehrere gleichzeitige Prozesse gegen denselben Store sind der Normalfall, nicht die Ausnahme --
mehrere Claude-Code-Sessions/Fenster, parallele Worktrees. Der RDF4J-NativeStore-Verzeichnis-Lock
wird fuer die gesamte Prozesslebensdauer gehalten (ab Store-Oeffnen bis Prozessende), nicht pro
Transaktion -- ein stdio-Subprozess pro Session oeffnet fuer denselben Workspace also mehrfach
denselben Store und kollidiert am Lock, sobald ein zweiter Prozess laenger als ganz kurz laeuft.

Neben dieser Kollision gleichzeitiger Prozesse eines Workspace steht die zweite Kraft: mehrere
verschiedene arknet-Workspaces auf einer Maschine muessen sich nebenlaeufig betreiben lassen,
ohne einander zu behindern. Rahmenbedingungen: arknet bleibt ein lokaler Single-User-Client
(ADR-001, kein Auth/Team), bleibt auf der Spring-AI-2.0-Tech-Linie (ADR-004), und soll kein
zusaetzliches Server-Produkt und keine Erweiterung von kognio-rdf erfordern.

## Entscheidung

arknet-mcp laeuft als EIN langlebiger Daemon-Prozess, der ueber Streamable HTTP auf Loopback
(`127.0.0.1`) erreichbar ist und ALLE Workspaces der Maschine bedient -- nicht als stdio-Subprozess
pro Session und nicht als ein Daemon pro Workspace.

1. Transport: dieselbe Spring-AI-2.0-Tech-Linie aus ADR-004, nur der HTTP-Webmvc- statt der
   stdio-Transport-Baustein.
2. Ein einziger Prozess auf einem festen Loopback-Port haelt die Stores aller Workspaces. Der
   Lifecycle-/Repository-Layer ist bereits mandantenfaehig -- jede Repository-Methode erwirbt ihr
   Dataset pro Aufruf ueber eine explizite DatasetId --, sodass ein Prozess mehrere Workspaces
   unter einem Storage-Root ohne Lock-Konflikt verwaltet.
3. Welchen Workspace ein Tool-Aufruf trifft, wird PRO AUFRUF aus dem Herkunftsverzeichnis des
   aufrufenden Clients aufgeloest, nicht einmalig beim Boot festgelegt. Der Client traegt sein
   Projektwurzel-Verzeichnis (sein `${PWD}`) in einem HTTP-Header (`X-Arknet-Workspace-Dir`); der
   Server leitet daraus per git-common-dir (#136) die WorkspaceId ab. Ein Aufruf ohne Herkunft
   faellt auf den Default-Workspace des Servers zurueck.
4. Loopback-only, ohne Authentifizierung -- dieselbe Vertrauensgrenze wie der vorherige
   stdio-Subprozess (nur lokale Prozesse derselben Maschine haben Zugriff). Der Herkunfts-Header
   ist ausdruecklich KEINE Authentifizierung: ein lokaler Client koennte ein fremdes
   Workspace-Verzeichnis behaupten -- an dieser Single-User-/Loopback-Grenze eine bewusst
   akzeptierte Annahme. Diese Annahme deckt nicht nur Workspace-*Routing*, sondern auch eine
   konkrete Dateisystem-Capability: `store_overview` schreibt `store-report.html` unvalidiert in
   den vom Header behaupteten Pfad (`Path.of(originDir)`, `Files.createDirectories`) -- vor #137
   schrieb es nur in ein fest konfiguriertes, admin-kontrolliertes Verzeichnis. Jeder lokale
   Aufrufer, der den Loopback-Port erreicht, kann den Daemon-Prozess damit veranlassen, ein
   beliebiges Verzeichnis anzulegen und hineinzuschreiben -- kein neuer Zugriffsvektor gegenueber
   dem bereits akzeptierten "kann Workspace X vortaeuschen" (Store-Lesezugriff war genauso offen),
   aber eine zusaetzliche, hier bewusst mitakzeptierte Capability (Schreibzugriff statt nur
   Store-Zugriff), keine Einschraenkung auf ein Unterverzeichnis des Workspace vorgesehen.
5. Der Prozess-Lifecycle liegt beim Menschen, nicht bei Claude Code: Claude Code verbindet sich
   nur gegen die konfigurierte HTTP-URL und setzt den Header, spawnt und verwaltet aber keinen
   Prozess mehr (fuer HTTP-Eintraege in `.mcp.json` gibt es dafuer keinen Mechanismus).

## Konsequenzen

**Positiv:** Die Lock-Kollision zwischen mehreren gleichzeitigen Sessions/Worktrees desselben
Workspace ist strukturell aufgeloest -- nur noch ein Prozess oeffnet je den Store. Die
Port-Kollision zwischen verschiedenen Workspaces derselben Maschine ist es ebenfalls: ein Port
fuer alle, der Workspace wird ueber den Header unterschieden, statt jedem Workspace einen eigenen
Port zuweisen zu muessen. Es war dafuer keine neue Store-Pool-Infrastruktur noetig -- die
Mandantenfaehigkeit sass bereits im Lifecycle-Layer; nur die MCP-Tool-Schicht wich von einer beim
Boot eingefrorenen Singleton-WorkspaceId auf die Per-Call-Aufloesung. Kein zusaetzliches
Server-Produkt, kein neues Protokoll, keine Erweiterung von kognio-rdf.

**Negativ / bewusst deferred:** Wer arknet nutzt, muss den Daemon-Prozess selbst starten und am
Leben halten -- Claude Code bietet dafuer keinen Lifecycle-Mechanismus fuer HTTP-MCP-Eintraege.
Der `${PWD}`-Header traegt Umgebungsvariablen-Semantik, kein dynamisches Arbeitsverzeichnis: fuer
git-Projekte faengt die git-common-dir-Aufloesung jedes Unterverzeichnis ab, fuer nicht-git-
Projekte ist es das Startverzeichnis der Session (Betriebshinweis: aus dem Projektverzeichnis
starten). Der Header traegt keine Authentifizierung -- fuer Single-User/Loopback akzeptiert; ein
Remote-/Team-Modus (ADR-003) braeuchte echte Auth und ist hier bewusst nicht geloest.

## Alternativen

- **Ein Daemon pro Workspace, fester Port je Workspace.** War der erste Schnitt dieser
  Transport-Umstellung. Braeuchte eine workspace-abgeleitete Portvergabe UND ein clientseitiges
  Wissen, welcher Port zu welchem Projekt gehoert -- `.mcp.json` kann aber keinen Port aus dem
  Verzeichnis ableiten. Der geteilte Server mit Per-Call-Header loest die Port-Kollision einfacher
  (ein Port, Workspace im Header). Verworfen zugunsten des geteilten Servers.
- **MCP `_meta` pro Tool-Call als Traeger der WorkspaceId.** Waere der protokolleigene Kanal fuer
  Per-Call-Kontext, aber Claude Code bietet keinen Weg, `_meta` pro Aufruf zu setzen. Nutzbar ist
  clientseitig nur der `.mcp.json`-`headers`-Eintrag mit `${PWD}`-Expansion (`${CLAUDE_PROJECT_DIR}`
  wird dort NICHT expandiert -- das ist ein Hooks-, kein Header-Feature). Verworfen (clientseitig
  nicht setzbar).
- **RDF4J Server (`rdf4j-http-server`).** Offiziell nur als WAR fuer Tomcat/Jetty dokumentiert,
  kein verifizierter leichtgewichtiger Embed-Pfad in eine eigene Spring-Boot-JVM. Verworfen (zu
  schwer fuer die Community Edition).
- **RDF4J `LmdbStore` statt `NativeStore`.** Echte Multi-Prozess-Faehigkeit (Lock nur pro
  Transaktion) waere die Wurzelloesung ohne jeden Server. Weiterhin als Experimental markiert,
  braucht native (LWJGL-)Abhaengigkeiten, und die Store-Auswahl liegt in `io.kogn.rdf`
  (kogn-io/rdf-core), nicht in arknet selbst. Verworfen (Risiko + Fremdabhaengigkeit ausserhalb
  arknets eigenem Einflussbereich).
- **Lokale Leader-Election ueber den bestehenden NativeStore-Lock** (Sidecar-Datei mit
  `{pid, port}`, Follower verbinden sich per Connect-Probe, kein zentraler Admin-Start noetig).
  Braucht keinen manuellen Start, aber mehr bewegliche Teile (Sidecar-Protokoll, Jitter-Backoff,
  Hand-off bei Leader-Crash). Verworfen zugunsten des einfacheren zentralisierten Daemons.
- **Adapter B / kognio-memory (ADR-003) als Unterbau.** Ist der Remote-Team-Store mit eigenem
  Auth, Closed Edition (ADR-002) -- wuerde ADR-001 Punkt 1 ("lokaler Single-User-Client") fuer die
  Community Edition brechen, fuer ein rein lokales Mehrprozess-Problem. Verworfen.
