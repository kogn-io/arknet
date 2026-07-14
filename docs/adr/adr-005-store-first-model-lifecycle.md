# ADR-005: Store-first -- das Modell lebt im Store, nicht in der Datei-Pipeline

- Status: Proposed (2026-07-14)
- Verwandt: ADR-001

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
