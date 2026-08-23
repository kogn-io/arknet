# neu-02: arknets Modell-Kontexte folgen dem Zielschnitt -- aus sechs werden drei

- Status: Proposed (2026-08-23)

## Kontext

arknet fuehrt sieben Hexagons: sechs Modell-Kontexte (requirements, use-cases,
ubiquitous-language, bounded-context, adr, actor) plus project als Registry. Dieser Schnitt
ist PR fuer PR gewachsen, nicht aus einer Kontextanalyse hervorgegangen.

Die Belege fuer zu feine Grenzen liegen seit Monaten vor: Cross-Kontext-Kanten sind der
Normalfall statt der Ausnahme (`usesTerm`, `stepRealises`, `primaryActor`,
`ubiquitousLanguageTerm`, `addressesRequirement`, `affectsContext`); ADR-008 existiert nur,
um eine selbstgesetzte Invariante wieder aufzuweichen, die zwischen diesen Grenzen zu strikt
war; alles laeuft in einem Prozess gegen einen Store, der uebliche Nutzen einer
Kontexttrennung wird also gar nicht eingeloest.

Der Zielschnitt (neu-01) legt anders: Use Case und Primaerakteur liegen dort im selben
Kontext wie Requirements; Glossar, Kontextkarte und Kontextbeziehung liegen zusammen;
Architekturbeschreibung und Entscheidungsrecords liegen zusammen.

## Entscheidung

arknets Modell-Kontexte folgen dem Zielschnitt. Drei statt sechs:

| Kontext | Inhalt heute |
|---|---|
| **Product & Requirements** | Requirement, Constraint, AcceptanceCriterion, UseCase samt Schritten, Traceability |
| **Domaenenmodellierung** | Glossarbegriff (SKOS), BoundedContext, ContextRelationship |
| **Architektur & Entscheidungen** | ArchitectureDecisionRecord |

`project` bleibt ausserhalb der Modell-Kontexte: es verwaltet Identitaet, nicht Modell, und
ist Werkzeug-Infrastruktur. Actor siehe neu-03.

Zwei Abgrenzungen, damit die Entscheidung nicht mehr behauptet als sie soll:

- **Kontextgrenze ist nicht Modulgrenze.** Ein Kontext darf mehrere Maven-Module halten -- der
  Zielschnitt selbst fuehrt 19 Module ueber 8 Kontexte. Diese Entscheidung legt fest, wo
  Sprachgrenzen verlaufen, nicht wie viele `pom.xml` es gibt.
- **Die Tool-Oberflaeche bleibt.** Die Praefixe `req_*`, `uc_*`, `term_*`, `bc_*`, `adr_*`,
  `actor_*` werden **nicht** umbenannt. Sie benennen Ressourcentypen, nicht Kontexte, und
  jede Umbenennung braeche jede bestehende Skill- und Client-Nutzung ohne fachlichen Gewinn.

## Konsequenzen

**Positiv:** Die dichtesten Cross-Kontext-Kanten (`stepRealises` von UseCase zu Requirement,
`ubiquitousLanguageTerm` von BoundedContext zu Term) werden zu kontextinternen Kanten und
brauchen keine Gateway-Konstruktion mehr. Der Schnitt laeuft auf das Zielbild zu, statt quer
dazu zu liegen -- kuenftige Kontexte werden angebaut, nicht eingepasst.

**Negativ:** Umbau an Modulgrenzen, ArchUnit-Regeln und Composition Root, ohne dass ein
Nutzer davon etwas sieht. Und die Entlastung ist nur teilweise: `usesTerm` von Requirement
und UseCase ins Glossar bleibt eine Kante ueber eine Kontextgrenze, ebenso
`addressesRequirement` und `affectsContext` vom ADR aus. ADR-008s Muster wird also seltener
gebraucht, aber nicht ueberfluessig -- wer das Gegenteil erwartet, wird enttaeuscht.

## Alternativen

- **Schnitt lassen wie er ist.** Verworfen -- er ist nicht klein, sondern fein, und laeuft
  nicht auf das Zielbild zu.
- **Ein einziger Kontext fuer alles.** Verworfen -- zwischen der Sprache der Requirements
  ("verifizierbar", "Akzeptanzkriterium"), der Sprache des Glossars ("Begriff", "Definition",
  "breiter/enger") und der Sprache der Entscheidungen ("Kontext", "Konsequenz",
  "superseded") verlaufen echte Sprachbrueche.
- **Erst die Module umschneiden, Kontextgrenzen spaeter.** Verworfen -- dann entscheidet
  wieder der Bauzustand ueber die Grenze statt umgekehrt.
