# ADR-005: Store-first -- das Modell lebt im Store, nicht in der Datei-Pipeline

- Status: Proposed (2026-07-14)
- Verwandt: ADR-001, ADR-011 (entscheidet, dass der hier entfallene Datei-Pfad nicht als
  diffbarer Export zurueckkehrt)

## Kontext

arknet stammt aus doc42 mit einer Datei-Pipeline: eine Turtle-Datei ist die Wahrheit, ein
Agent editiert sie als Text und faehrt sie durch load -> validate -> query -> generate.
Parallel ist mit den hexagonalen Bounded Contexts (requirements) ein Store hinter
domaennahen Out-Ports entstanden (ADR-001), den ein Agent ueber schreibende MCP-Tools
fuellt, mit SHACL-Gate beim Write.

Damit standen zwei Modell-Lebenszyklen nebeneinander: die Datei als Source of Truth
(Text-Edit an der .ttl) gegen den Store als Source of Truth (Schreiben ueber BC-Tools).
Beide gleichzeitig zu pflegen streut den Modellzustand ueber Datei und Store, verdoppelt
Validierungs- und Provenance-Wege und zwingt zu staendiger Synchronisation. Es war zu
entscheiden, welcher der primaere ist.

## Entscheidung

Der Store ist der primaere Ort des Modells; die Datei-Pipeline ist nicht mehr der primaere
Modell-Lebenszyklus.

1. Modell-Elemente werden ueber domaennahe, store-basierte MCP-Write-Tools im BC-Stil
   (`req_*`, kuenftig `term_*` usw.) verwaltet -- nicht durch Agent-Edits an einer .ttl.
2. Die datei-basierten `arknet_*`-Tools (load/validate/query/generate) gelten als
   aussterbend: geduldet fuer Import und Interop, aber kein Ziel weiterer Investition.
3. Projektionen lesen aus dem Store, nicht aus einer Datei.
4. Reverse-Engineering aus Code fuellt kuenftig den Store ueber dieselben Write-Tools,
   statt eine .ttl zu generieren; der bisherige datei-generierende Analyse-Weg entfaellt.

## Konsequenzen

**Positiv:**

- Ein Modellzustand, eine Wahrheit -- kein Auseinanderlaufen von Datei und Store, kein
  Datei-Merge-Problem zwischen generierten und manuellen Aussagen.
- SHACL-Validierung greift einheitlich am Write-Gate des Stores statt als separater
  Datei-Schritt; Provenance lebt als Store-Aussagen statt als Tags in einer Datei.
- Die WorkspaceId (ADR-001) selektiert das Modell einheitlich fuer Lese- und Schreibpfad.

**Negativ / bewusst deferred (YAGNI):**

- Ein store-basierter Reverse-Engineering-Weg (Code -> Store) existiert noch nicht; bis
  dahin gibt es keinen automatischen Bulk-Import aus Bestandscode. Bewusst offen, bis der
  Bedarf konkret ist.
- Das Ontologie-Vokabular des alten datei-basierten Analyse-Laufs (`arknet:AnalysisRun`
  und Verwandtes) bleibt vorerst als toter Ballast im Metamodell; seine Entfernung ist eine
  breaking Aenderung und separat zu entscheiden.
- Die reine .ttl-Datei als erstklassiges Austausch- und Versionierungsformat verliert ihren
  Rang; wer eine Datei als Interop-Format braucht, geht ueber Import/Export statt ueber den
  primaeren Lebenszyklus.

## Alternativen

- **Datei bleibt Source of Truth, Store nur fluechtiger Cache.** Verworfen: verschenkt
  Write-Gate, Provenance und Multi-Projekt-Routing des Stores; der Agent editiert weiter
  Text-Turtle und raet das Schema.
- **Beide Lebenszyklen dauerhaft gleichwertig fuehren (Datei UND Store als Wahrheit).**
  Verworfen: doppelter Validierungs- und Provenance-Weg plus staendige Synchronisation --
  genau die Zustandsstreuung, die vermieden werden soll.

## Nachtrag 2026-07-17 (#75): Duldungsklausel kassiert, Datei-Pipeline entfernt

Punkt 2 der Entscheidung und der dritte Negativ-Punkt der Konsequenzen setzten eine Duldung
voraus: die datei-basierten `arknet_*`-Tools (`arknet_load`/`arknet_validate`/`arknet_query`/
`arknet_generate`/`arknet_list_queries`/`arknet_list_projections`) bleiben "aussterbend, aber
geduldet fuer Import und Interop" -- wer eine Datei als Interop-Format braucht, geht ueber
Import/Export statt ueber den primaeren Lebenszyklus. Diese Klausel wird hiermit **kassiert**,
nicht nur vollzogen: der Code, der sie eingeloest haette, ist entfernt.

**Grund fuer die Verschaerfung:** Die Pruefung gegen den Code zeigte, dass die Duldung nie einen
realen Import gedeckt hat. `ArknetEngine` hielt einen eigenen `SailRepository(new MemoryStore())`,
vollstaendig getrennt vom Workspace-`DatasetLifecycle`. `arknet_load` hat **nie** in den Store
importiert -- es fuellte einen Wegwerf-In-Memory-Store, der bei Prozessende verschwand. Die
"zwei Wahrheiten nebeneinander" aus dem Kontext-Abschnitt waren also keine zwei gleichwertigen
Modell-Lebenszyklen, sondern ein gelebter (Store) und ein fiktiver (Datei-Import, der nirgendwo
ankam). Die Duldung deckte damit die Fiktion eines Imports, nicht einen funktionierenden.

**Entfernt** (#75): die Module `arknet-core` (`ModelLoader`, `ValidationReport`,
`ValidationResult`, `SparqlExecutor`) und `arknet-projection` (vier Projektionen, drei Mustache-
Templates, sechs `.sparql`-Dateien) vollstaendig; aus `arknet-mcp` die Klassen `ArknetEngine` und
`ArknetTools` sowie deren zwei Beans in `ArknetMcpConfiguration`. Wiederherstellbar ueber den
Abriss-Commit dieses Nachtrags (Muster: doc42-origin, Import-Commit `139bb86`).

**Bleibt unveraendert:** `arknet-ontology` (alle `.ttl`-Module) -- der `ModelLoader` war nur *ein*
Konsument des Metamodells, nicht sein Eigentuemer; alle drei BC-Out-Adapter laden ihre
`.ttl`/Shapes direkt aus `arknet-ontology`, nichts lief transitiv ueber `arknet-core`.
`arknet-process.ttl`, `arknet-architecture.ttl`, `arknet-privacy.ttl` bleiben als Vorrat fuer
kuenftige BCs liegen. Das self-contained `store-report.html` (#47, `store_overview`) bleibt der
einzige *generierende* Ausgabepfad -- kein AsciiDoc, kein PDF, kein PlantUML mehr, bis ein
Nachfolger (#16) neu entscheidet, ob eine Template-Engine in der MCP-first-Welt ueberhaupt noch
das richtige Werkzeug ist.

Der dritte Negativ-Punkt der Konsequenzen ("wer eine Datei als Interop-Format braucht, geht ueber
Import/Export") ist damit ebenfalls ueberholt: es gibt aktuell **keinen** Import/Export-Pfad mehr,
weder einen funktionierenden noch einen fiktiven. Ein store-basierter Reverse-Engineering-Weg
(erster Negativ-Punkt oben) bleibt so lange offen, bis der Bedarf konkret ist.
