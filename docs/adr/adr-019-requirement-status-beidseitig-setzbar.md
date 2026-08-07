# ADR-019: Requirement-Status bleibt ohne Durchsetzung, ist aber beidseitig setzbar

- Status: Accepted (2026-08-06)
- Supersedes: ADR-018

## Kontext

`arkreq:RequirementStatus` traegt sechs Ontologie-Werte (Proposed, Accepted, Implemented,
Verified, Rejected, Deprecated) ohne OSLC-RM-Aequivalent und ohne begruendendes ADR -- die
Requirements-Ontologie fuehrt den Lebenszyklus als arknet-eigene Ergaenzung ein, OSLC RM
ueberlaesst Status bewusst jedem RM-Tool. Von den sechs Werten sind ueber die Tool-Oberflaeche
nur zwei erreichbar (Proposed, Accepted). Ein Statuswechsel hat keinerlei Konsequenz: keine
Kopplung an die Erfuellungs- oder Dekompositionskanten eines Requirements, keine SHACL-Regel,
kein Architekturtest. Einziger Konsument ist die Report-Darstellung, die den Wert als Text bzw.
eingefaerbte Pille zeigt -- ein rein visuelles Signal ohne Verhaltensfolge.

Eine Ausnahme davon ist implementiert: der Weg zurueck ist versperrt. `RequirementMcpTools#accept`
(arknet-requirements-adapter-mcp) weist jeden Zielstatus ausser `ACCEPTED` explizit ab; der
`status`-Parameter existiert laut eigenem Kommentar nur noch "for API stability".
`Requirement#accept()` (arknet-requirements-core) ist kein Setter, sondern die Uebergangsregel
selbst: bereits `ACCEPTED` bleibt ein No-op, `PROPOSED` wird `ACCEPTED`, jeder andere Ausgangswert
faellt in eine `IllegalStateException`.

Damit laesst sich das Signal setzen, aber nicht zurueckziehen -- waehrend der *Inhalt* des
Requirements frei aenderbar bleibt, denn `req_update` ist statusunabhaengig. Das Statusfeld
behauptet weiterhin "abgestimmt", der Text darunter wandert weiter, und die Behauptung ist nicht
widerrufbar. Ein Statuswert ohne Weg zurueck ist ein Wert, den zu setzen irrational ist.

Der Widerspruch trat im Brownfield-Selbstinterview der Requirements-BC zutage, beim Zuschnitt des
Use Case "Requirement akzeptieren": ein Statusmechanismus, der nichts bewirkt ausser einzufaerben,
laesst sich nicht sinnvoll als Use Case beschreiben, ohne eine Wirkung zu unterstellen, die es
nicht gibt -- und eine Einbahnstrasse laesst sich in diesem Use Case erst recht nicht begruenden.

Erwogen und weiterhin zurueckgestellt ist ein Ausbau mit echten Konsequenzen -- etwa ein Gating,
das die Erfuellungs- oder Dekompositionskante eines Requirements an dessen Status und den Status
verwandter Ressourcen koppelt. Zwei Vorbedingungen dafuer fehlen im Modell:

- Das Requirements-Metamodell kennt keinen einzigen Eltern-Kind-Baum, sondern drei Kantentypen mit
  unterschiedlicher Kardinalitaet: Komposition (Requirement zu AcceptanceCriterion, UseCase zu
  Step; exklusives 1:N-Eigentum), Dekomposition (Requirement zu Requirement ueber
  `oslc_rm:decomposedBy`; ein echter Baum) und Erfuellung (UseCase bzw. Step zu Requirement ueber
  `oslc_rm:satisfies`/`stepRealises`; M:N, seit #266 nicht einmal verpflichtend). Eine
  Gating-Regel muesste vorab festlegen, welcher dieser Kantentypen ueberhaupt "Eltern-Kind" im
  Sinn der Regel meint.
- Die sechs Statuswerte sind unverbundene `owl:NamedIndividual`s ohne festgelegte Reihenfolge.
  Eine Regel wie "nicht akzeptierbar, solange eine verwandte Ressource noch nicht akzeptiert ist"
  setzt eine Ordnungsrelation zwischen den Statuswerten voraus, die es noch nicht gibt.

Eine Baseline-Freeze-Semantik -- akzeptiert heisst eingefroren -- bleibt verworfen: sie setzt einen
Apparat voraus (Requirement-Versionierung, eine Change-Request-Ressource, eine genehmigende Rolle),
den arknet nicht hat.

## Entscheidung

Requirement-Status bleibt ohne Durchsetzung und ist in beide Richtungen setzbar.

1. Der Status ist ein unverbindliches Reifegrad-Signal fuer den lesenden Menschen, ohne
   Durchsetzung.
2. Die Tool-Oberflaeche erreicht weiterhin nur Proposed und Accepted; die vier weiteren
   Ontologie-Werte bleiben ungenutztes Vokabular.
3. Ein Statuswechsel loest keine Konsequenz an anderen Ressourcen oder Kanten aus -- keine Kopplung
   an Erfuellungs- oder Dekompositionskanten, keine SHACL-Regel, kein Architekturtest -- und es gibt
   keine Reihenfolge- oder Gating-Beziehung zwischen dem Status eines Requirements und dem Status
   oder Vorhandensein verwandter Ressourcen.
4. Der Status ist in beide Richtungen setzbar, `Proposed` ebenso wie `Accepted`. Die heute
   implementierte Einbahnstrasse ist ein Defekt (#291), keine Entscheidung: ein unverbindliches
   Reifegrad-Signal, das nur in eine Richtung zeigt, waere gerade keines.

## Konsequenzen

**Positiv:** Die Requirements-BC bleibt einfach -- kein Erzwingungsmechanismus, keine Vorwegnahme
eines Change-Request-Apparats, den arknet nicht hat. Der Use Case "Requirement akzeptieren" laesst
sich ehrlich beschreiben: er setzt das Statusfeld, sonst nichts, und eine verfrueht erteilte
Akzeptanz laesst sich zurueckziehen.

**Negativ / bewusst deferred (YAGNI):** Der Status bleibt kosmetisch und kann falsche
Verbindlichkeit suggerieren -- eine gruen gefaerbte Pille im Report sieht nach Freigabe aus, ist
aber keine. Vier der sechs Ontologie-Werte bleiben totes, unerreichbares Vokabular. Ein spaeterer
Ausbau (echte Konsequenzen, Status-Gating zwischen verwandten Ressourcen) setzt voraus, dass zuerst
eine explizite Ordnungsrelation der Statuswerte entsteht und -- sofern eine Ruecksprung-Sperre
gewuenscht ist -- ein Change-Request-Apparat (Versionierung, eigene Ressource, genehmigende Rolle).
Diese Vorarbeit ist in #289 festgehalten und hier bewusst nicht geleistet.

Mit dem Fix aus #291 setzt die Implementierung Punkt 4 dieser Entscheidung vollstaendig um:
`req_set_status` erreicht sowohl `Proposed` als auch `Accepted`, die zuvor bestehende
Ruecksprungsperre ist behoben.

## Alternativen

- **Status ausbauen (echte Konsequenzen, volle Enum-Erreichbarkeit, Gating zwischen Requirement und
  abhaengigen Ressourcen).** Verworfen fuer jetzt -- setzt eine Ordnungsrelation der Statuswerte und
  eine Klaerung voraus, welcher Kantentyp "Eltern-Kind" bedeuten soll; ohne beides waere ein Gating
  willkuerlich.
- **`status`-Property ersatzlos entfernen.** Verworfen -- der informelle Reifegrad ist im Report
  bereits ein genutztes, wenn auch schwaches Signal; Entfernen waere ein Rueckschritt.
- **Enum auf die zwei erreichbaren Werte (Proposed/Accepted) reduzieren.** Verworfen fuer jetzt --
  ob Implemented/Verified/Rejected/Deprecated spaeter Sinn ergeben, ist eine eigene Frage und nicht
  Teil dieser Zurueckstellung.
- **Die Einbahnstrasse als gewollt bestaetigen.** Verworfen -- das waere Baseline-Freeze ohne den
  dafuer noetigen Apparat, und ein Statuswert, den zu setzen unumkehrbar ist, wird zu einem, den zu
  setzen irrational ist.
