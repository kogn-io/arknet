# tmp: Zielsatz der Architekturentscheidungen

**Nicht der Bestand.** Dieses Verzeichnis haelt einen Entwurf des Entscheidungssatzes, wie er
heute (2026-08-23) aussehen wuerde. Er ersetzt den Bestand in `docs/adr/` erst, wenn er
bestaetigt ist; bis dahin gilt der Bestand.

## Was das hier ist -- und was nicht

Es ist **kein Rueckschreiben der Historie.** Ein ADR haelt eine Entscheidung mit dem Kontext
fest, der zum Entscheidungszeitpunkt galt; schriebe man sie heute mit heutigem Wissen neu,
entstuende ein in sich stimmiger, aber falscher Satz Records.

Diese Records sind stattdessen **neue Entscheidungen von heute**, mit heutigem Datum, die den
alten Satz ersetzen. Damit ist das Loeschen der abgeloesten Dateien unproblematisch: sie
werden nicht geschoent, sie werden abgeloest.

## Zuordnung Bestand -> Zielsatz

| Zielsatz | ersetzt | Aenderung gegenueber dem Bestand |
|---|---|---|
| neu-01 Domaenenanalyse als Scope-Rahmen | ADR-017 | Rahmen ist das Zielbild, nicht der Bauzustand; Ungebautes ist "noch nicht", nicht "ausserhalb" |
| neu-02 Modell-Kontexte folgen dem Zielschnitt | -- (neu) | sechs Modell-Kontexte werden drei |
| neu-03 Actor als Querschnitt | ADR-022 (in PR #330, ungemergt) | Actor ist kein eigener Kontext |
| neu-04 Lokaler Client, austauschbarer Store | ADR-001 | ohne CLI und `arknet-core` (existieren nicht mehr); ProjectId statt WorkspaceId |
| neu-05 Store-first | ADR-005 | Datei-Pipeline nicht mehr "aussterbend", sondern entfernt |
| neu-06 Generischer Store-Lesepfad | ADR-006 | ohne die Implementierungsdetails (Klassennamen, Fabrikmethoden) |
| neu-07 Spring AI als MCP-Tech-Linie | ADR-004 | unveraendert in der Sache |
| neu-08 Ein geteilter HTTP-Daemon | ADR-009 | Projektwahl ueber den Anker statt ueber das Arbeitsverzeichnis |
| neu-09 Projekt-Identitaet ueber Anker | ADR-016 | unveraendert in der Sache |
| neu-10 Commit-Provenance statt Export | ADR-011 | unveraendert in der Sache |
| neu-11 Revision als Provenance und Token | ADR-013 + ADR-014 | zwei Records zu einer Entscheidung zusammengefasst |
| neu-12 Requirement-Status ohne Durchsetzung | ADR-018 + ADR-019 | ADR-018 war bereits abgeloest; eine Entscheidung statt zweier |

## Unveraendert uebernommen, nicht neu geschrieben

Aus PR #330 (offen, nicht gemergt). Diese drei sind am 2026-08-18/19 unter heutigem
Verstaendnis entstanden und behalten **Nummer und Inhalt** -- ein Umschreiben braechte
nichts und wuerde offene Umsetzungs-Issues ins Leere zeigen lassen:

| Record | Grund |
|---|---|
| ADR-020 Actor breit gefasst, Stakeholder ist eine Rolle | deckt sich mit dem Zielbild (dort ist Stakeholder eine Rolle, keine Klasse) |
| ADR-021 `arkreq:Goal` ersatzlos entfernt | **Umsetzung offen: Issue #344 verweist auf diese Nummer** |
| ADR-023 Requirement-Herkunftskante zum Actor | **Umsetzung offen: Issue #345 verweist auf diese Nummer** |

Nur **ADR-022** aus demselben PR faellt -- abgeloest durch neu-03.

## Ersatzlos zu loeschen

Keine Entscheidung, die im Zielsatz fehlt, geht dabei verloren -- das ist die Behauptung, die
beim Review zu pruefen ist.

| Record | Grund |
|---|---|
| ADR-003 Adapter B (Remote-Store) | ungebaut, Spekulation; seit Juli Proposed |
| ADR-007 Geteiltes SHACL-Write-Gate | Implementierungsentscheidung ueber einen Modulschnitt |
| ADR-008 In-Adapter als Kontext-Gateway | weicht eine selbstgesetzte Invariante auf; mit neu-02 gegenstandslos |
| ADR-010 Review-UI als Vaadin-Adapter | ungebaut, Spekulation; seit Juli Proposed |
| ADR-012 Plugin-/Service-Repo-Split | Organisations- und Release-Entscheidung, kein Architektur-Entscheid |
| ADR-015 Domaenentypen bleiben Records | Implementierungsentscheidung; **strittigster Loeschkandidat**, siehe unten |

## Offene Punkte fuer den Review

1. **ADR-015** ist der unsicherste Loeschvorschlag. Er entscheidet gegen graph-gestuetzte
   Domaenenobjekte und gegen einen Konstruktions-Out-Port -- das grenzt Domaene gegen Substrat
   ab und ist damit naeher an Architektur als die uebrigen Loeschkandidaten. Bewusst zur
   Entscheidung gestellt statt still behalten oder still geloescht.
2. **Nummerierung beim Umzug.** Die `neu-NN`-Nummern sind Platzhalter. Zu entscheiden: durchweg
   neu nummerieren (dann zeigen Issues #344/#345 ins Leere und muessen angepasst werden) oder
   die Nummern der uebernommenen Records halten und nur die ersetzten neu vergeben.
3. **PR #330.** Er traegt ADR-020/021/022/023. Da 022 faellt, 020/021/023 aber bleiben und
   #344/#345 an ihnen haengen: entweder den PR ohne ADR-022 mergen, oder ihn vollstaendig
   mergen und ADR-022 unmittelbar danach durch neu-03 abloesen.
4. **ADR-002** existiert nicht und hat nie existiert. Beim Umzug entweder die Luecke
   uebernehmen oder lueckenlos durchnummerieren.
