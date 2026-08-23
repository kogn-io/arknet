# neu-05: Der Store ist der einzige Modell-Lebenszyklus

- Status: Proposed (2026-08-23) -- ersetzt ADR-005

## Kontext

arknet begann als Datei-Pipeline: der Agent editierte `.ttl`-Dateien, ein Ladewerkzeug
validierte sie gegen SHACL und schob sie in einen Store. Das Modell lebte in Dateien, der
Store war eine Ableitung davon.

Diese Bauweise hat drei Probleme, die sich nicht wegkonfigurieren lassen: der Agent muss
Turtle-Syntax korrekt erzeugen, bevor irgendeine fachliche Pruefung greift; zwei Sessions am
selben Modell haben keinen gemeinsamen Stand; und jede fachliche Regel muss doppelt existieren
-- einmal als SHACL, einmal als Erwartung an den Editierenden.

Der Ausschnitt aus dem Zielbild (neu-01) ist ein Werkzeug, das Artefakte **verwaltet**. Ein
verwaltetes Artefakt hat einen Lebenszyklus, keinen Dateipfad.

## Entscheidung

Der Store ist der primaere und einzige Ort des Modells.

1. Modellelemente werden ueber domaennahe, store-basierte Write-Tools verwaltet (`req_*`,
   `uc_*`, `term_*`, `bc_*`, `adr_*`, `actor_*`, `constraint_*`) -- nicht durch Agent-Edits an
   einer Datei.
2. Es gibt keine datei-basierte Pipeline mehr. Die frueheren `arknet_*`-Tools und die Module
   `arknet-core` und `arknet-projection` existieren nicht mehr; sie werden nicht wiederbelebt.
3. Datei-Ausgabe existiert nur als Ergebnis, nie als Eingang: der self-contained
   `store-report.html` und der Volldump zu Backup-Zwecken (neu-10).
4. Fuellt kuenftig ein Reverse-Engineering aus Code den Store, tut es das ueber dieselben
   Write-Tools.

## Konsequenzen

**Positiv:** Jede Schreiboperation laeuft durch dieselbe fachliche Pruefung, unabhaengig davon,
wer schreibt. Mehrere Sessions teilen einen Stand. Der Agent muss keine Syntax beherrschen,
nur Absichten formulieren.

**Negativ:** Es gibt kein `git diff` auf das Modell. Wer Aenderungen nachvollziehen will,
braucht die Provenance im Store (neu-10, neu-11) -- eine schwerere Maschinerie als eine
sortierte Textdatei, und sie ist nur ueber arknet lesbar. Ein Import aus fremden
`.ttl`-Bestaenden hat heute keinen Weg.

## Alternativen

- **Datei und Store gleichrangig.** Verworfen -- zwei Wahrheiten, die auseinanderlaufen.
- **Datei primaer, Store als Index.** Verworfen -- genau der Ausgangszustand mit allen drei
  Problemen.
