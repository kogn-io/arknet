# ADR-012: Plugin und Service in getrennten Repositories statt einem Monorepo

- Status: Accepted (2026-07-25)
- Verwandt: ADR-002 (Open-Core-Editionen -- Lizenz/Distribution, orthogonal zur Repo-Struktur
  hier), ADR-009 (geteilter MCP-HTTP-Daemon -- der Service, dessen Betriebsmodell von dieser
  ADR unberuehrt bleibt)

## Kontext

Plugin (Claude Code Skills, `plugin.json`) und Service (`arknet-mcp`, Docker-Image) lebten
bislang im selben Repo (`kogn-io/arknet`), mit demselben Tag-Namespace fuer beide. Plugin-SemVer
(aus `plugin.json`) und Service-Version liefen bewusst getrennt (#150), der urspruengliche Plan
dafuer war ein Subdir-Schnitt (`plugin/` innerhalb desselben Repos, `git-subdir` als
Marketplace-Quelle).

Neuer, konkreter Treiber: `arknet-mcp` bekommt ein echtes, SemVer-getaggtes Docker-Release
(`ghcr.io/kogn-io/arknet:vX.Y.Z`, unveraenderlich) fuer einen externen Konsumenten, der arknet
bereits produktiv nutzt. Damit gibt es zwei echte, unabhaengige Release-Zyklen mit eigenem
SemVer-Anspruch: Plugin/Marketplace und Service/Docker. Ein Subdir *im selben Repo* loest das
nicht: beide haengen weiter an derselben CI und demselben Tag-Namespace (`vX.Y.Z` kollidiert),
und `git-subdir` klont laut Claude-Code-Doku ohnehin das volle Repo -- kein struktureller Gewinn
ausser Uebersicht.

## Entscheidung

Zwei Repositories statt eins:

1. **`kogn-io/arknet`** -- der Service: Java-Monorepo (`arknet-mcp` + die vier BC-Hexagons +
   Ontologie). Eine Versionsachse: Docker-Release-SemVer (`vX.Y.Z`-Tag auf
   `ghcr.io/kogn-io/arknet`, publiziert per manuellem `workflow_dispatch`-Release-Gate). Maven
   `<revision>` bleibt SNAPSHOT-Deko, kein zweiter Konsument mehr im selben Repo, der damit
   verwechselt werden koennte. Die Root-`.mcp.json` bleibt bestehen, fuers eigene Dogfooding
   gegen den hier gebauten Server.
2. **`kogn-io/arknet-plugin`** -- das Claude Code Plugin: `.claude-plugin/plugin.json`,
   `skills/{adr,req-interview}/`, eine eigene Distributions-`.mcp.json`. Eigene Versionsachse:
   Plugin-SemVer, eigenes Release-Gate (gleiches Muster: `workflow_dispatch`,
   SemVer-Validierung, Tag-Kollisions-Check, GitHub-Release-Objekt), eigener
   Marketplace-Eintrag als normale `git`-Quelle (kein `git-subdir` mehr noetig).

Die Plugin-Inhalte wurden history-erhaltend per `git subtree split`/`git subtree add`
uebertragen -- zwei getrennte Splits fuer `.claude-plugin/` und `skills/`, da beide
Top-Level-Verzeichnisse ohne gemeinsamen Prefix sind. Der Marketplace-Eintrag
(`ai-tools/claude-code-marketplace`) wurde auf die neue Repo-URL umgestellt.

## Konsequenzen

**Positiv:** Jedes Repo hat eine einzige, unzweideutige Versionsachse -- kein Tag-Namespace-
Konflikt mehr, kein Rätselraten, ob ein SemVer-Bump das Plugin oder den Service meint. Ein
Release-Workflow-Lauf betrifft nie versehentlich das andere Artefakt. Die Marketplace-Quelle
zeigt direkt auf den Plugin-Content, ohne `git-subdir`-Indirektion.

**Negativ / bewusst in Kauf genommen:**

- **Versions-Kompatibilitaet zwischen Plugin und Service ist jetzt ungeloest.** Solange beide im
  selben Repo/Commit lebten, war das automatisch konsistent. Jetzt releasen sie unabhaengig, und
  es gibt keinen Mechanismus, der erkennt, ob ein installiertes Plugin zu einem laufenden
  Server passt (#159, arknet-plugin#2).
- **`/arknet:adr` hat jetzt eine Dateipfad-Abhaengigkeit auf ein lokales `arknet`-Checkout**, die
  vorher implizit durch das gemeinsame Repo geloest war -- der Skill liest die Ontologie-Datei
  direkt aus `arknet-ontology/`, die nur im Service-Repo existiert (arknet-plugin#1).
- **Zwei Repos statt eines zu pflegen**, inklusive zwei getrennter Issue-Tracker-Entscheidungen
  (`arknet` bleibt beim bestehenden Forgejo-Tracker, `arknet-plugin` bekommt GitHub Issues --
  bewusst unterschiedlich, weil das Plugin-Repo deutlich kleiner ist).

## Alternativen

- **Subdir-Schnitt (`plugin/` im selben Repo) + `git-subdir` als Marketplace-Quelle.** Der
  urspruengliche Plan (#150). Verworfen, weil CI und Tag-Namespace weiter geteilt blieben und
  `git-subdir` laut Doku ohnehin das volle Repo klont -- kein struktureller Gewinn gegenueber
  einem echten zweiten Repo.
- **Alles in einem Repo lassen, nur den Plugin-Release-Tag anders praefixen** (z.B.
  `plugin-vX.Y.Z` statt `vX.Y.Z`). Loest die Tag-Kollision oberflaechlich, aendert aber nichts an
  der geteilten CI, dem gemeinsamen Marketplace-Klonziel oder daran, dass ein Service-Release
  versehentlich Plugin-Dateien im selben Commit mitzieht. Verworfen als kosmetischer statt
  struktureller Fix.
