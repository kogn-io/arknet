---
description: "ADRs in docs/adr/ schreiben, reviewen und pflegen -- gegen das arknet-Metamodell (arknet-architecture.ttl). Haelt ADRs als dauerhafte Entscheidungssaetze, nicht als Statusberichte. Trigger: /arknet:adr, 'schreib ein ADR', 'neues ADR', 'ADR fuer X', 'review das ADR', 'pflege die ADRs', 'ist das ein gutes ADR'. NICHT triggern bei: allgemeiner Doku (asciidoc-writer), Requirements (arkreq), Code-Kommentaren."
---

# /arknet:adr -- Architecture Decision Records pflegen

You maintain arknet's Architecture Decision Records in `docs/adr/`. Your single job:
keep every ADR a record of a **durable decision and its lasting consequences** -- never a
status report, never an implementation snapshot.

The authority is not taste. It is arknet's own metamodel:
**`arknet-ontology/src/main/resources/arknet-architecture.ttl`**, class
`arkarch:ArchitectureDecisionRecord`. arknet dogfoods it. If a sentence has no home in a
metamodel slot, it does not belong in the ADR. When in doubt, open the .ttl and check --
do not invent slots.

## The metamodel defines the ONLY allowed content

These are the slots `arkarch:ArchitectureDecisionRecord` permits. There are no others.

| Metamodell-Slot | Bedeutung (rdfs:comment) | Markdown-Abschnitt |
|-----------------|--------------------------|--------------------|
| `dcterms:identifier` | Nummer (Pflicht, genau 1) | Dateiname + Titel |
| `arkarch:adrStatus` | genau 1: Proposed / Accepted / Rejected / Deprecated / Superseded | `- Status:` Zeile |
| `arkarch:decisionDate` | Datum der Entscheidung | Datum in `- Status:` Zeile |
| `arkarch:adrContext` | "Warum musste entschieden werden? Kraefte und Rahmenbedingungen." | `## Kontext` |
| `arkarch:adrDecision` | "Die getroffene Entscheidung." | `## Entscheidung` |
| `arkarch:adrConsequences` | "Positive und negative Folgen der Entscheidung." | `## Konsequenzen` |
| `arkarch:adrAlternatives` | "Erwogene, aber verworfene Optionen mit Begruendung." | `## Alternativen` |
| `arkarch:relatedTo` / `supersedes` / `supersededBy` | Querverweise zwischen ADRs | `- Verwandt:` Zeile |
| `arkarch:addressesRequirement` | Traceability zu einem Requirement (#17) | optional in Kontext/Entscheidung |
| `arkarch:affectsContext` | betroffener Bounded Context | optional in Kontext/Entscheidung |

If you catch yourself writing an "Offene Punkte", "Umsetzung", "Status", "TODO", "Aktueller
Stand" or "Naechste Schritte" section -- **stop**. No such slot exists. That content belongs
elsewhere (see below).

## The Litmus Test (apply to every sentence)

> **Veraltet dieser Satz, sobald jemand Code aendert?**
> Ja  -> raus. Es ist Verdrahtungsstand, kein Entscheidungsinhalt.
> Nein -> es ist eine dauerhafte Konsequenz und darf bleiben.

Worked example (this exact edit was made to ADR-001):

- RAUS (Schnappschuss): "der aktuelle MCP-Bau pinnt sie auf `WorkspaceId.DEFAULT`, arbeitet
  also faktisch auf einem Workspace." -- veraltet, sobald jemand die Provenance verdrahtet.
- BLEIBT (dauerhafte Konsequenz): "Kein Workspace-Management und keine festgelegte Herkunft
  der WorkspaceId je Session -- bewusst offen, bis der Bedarf konkret ist."

Same decision, but one form is a decision record and the other is a status line pretending
to be one.

## Anti-Patterns -- the failure class to guard against

The recurring mistake is smuggling transient implementation state into a durable document.
Concretely, NEVER put these in an ADR:

1. **Implementierungs-Schnappschuss** -- "pinnt gerade auf X", "aktuell per lokalem Override
   in pom.xml geloest", "z.Zt. nur ein Adapter verdrahtet". Wiring state, not decision.
2. **Offene-Punkte- / TODO-Liste** -- offene Arbeit ist ein Issue, kein ADR-Abschnitt.
   (Eine *bewusst deferred* Konsequenz mit Begruendung ist erlaubt -- das ist eine Folge der
   Entscheidung, kein Arbeitszettel.)
3. **Commit-Refs / PR-Nummern / Datei-Zeilen** -- "siehe Commit fcfaf29", "in
   ModelLoader.java Zeile 42". Fluechtig; gehoert in Git/Issue, nicht ins ADR.
4. **"Umsetzung" / "Status" / "Naechste Schritte"-Sektion** -- kein Metamodell-Slot.
5. **Der Agent entscheidet selbst** -- ein ADR haelt *Freds* Entscheidung fest, nicht deine.
   Ist die Entscheidung noch nicht getroffen: Status `Proposed` und im Kontext offen benennen
   -- nicht eine Entscheidung erfinden, um den Slot zu fuellen.
6. **Leere / Pflicht-Alternativen** -- "Keine Alternativen erwogen" ist ein Geruch. Wenn
   wirklich alternativlos, kurz begruenden *warum* der Moeglichkeitsraum leer war.
7. **Marketing-Prosa / Fuellwoerter** -- nuechtern, dicht, ein Gedanke pro Satz.

## Wohin der ausgeschlossene Inhalt gehoert

Schnappschuss rausnehmen heisst nicht Information wegwerfen -- nur am richtigen Ort ablegen:

- **Verdrahtungsstand / "warum steht der Code so"** -> Javadoc am betroffenen Typ, oder eine
  `Note` im Wissensgraphen (kogn).
- **Offene Arbeit** -> Forgejo-Issue (siehe reference_forgejo im Memory).
- **Projekt-Fortschritt / "erledigt in Commit X"** -> Memory (`project_*`), Git-Historie.

Wenn du einen Schnappschuss aus einem ADR entfernst, pruefe kurz, ob er anderswo schon
festgehalten ist; wenn nicht, weise darauf hin (nicht stillschweigend loeschen).

## Template (Hausstil, deckt sich mit ADR-001..004)

```markdown
# ADR-NNN: <praegnanter Entscheidungstitel>

- Status: <Proposed|Accepted|Rejected|Deprecated|Superseded> (JJJJ-MM-TT)
- Verwandt: ADR-XXX, ADR-YYY        # weglassen, wenn keine

## Kontext

Warum musste entschieden werden? Kraefte und Rahmenbedingungen. Verweise auf verwandte ADRs
und -- falls zutreffend -- auf das adressierte Requirement / den betroffenen Bounded Context.

## Entscheidung

Die getroffene Entscheidung, praezise und im Aktiv. Bei mehreren Teilen nummerieren.

## Konsequenzen

**Positiv:** dauerhafte Vorteile, die aus der Entscheidung folgen.

**Negativ / bewusst deferred (YAGNI):** dauerhafte Kosten und bewusst offengelassene Punkte
*mit Begruendung*. Kein Arbeitszettel, kein Schnappschuss.

## Alternativen

- **<Verworfene Option>.** Ein Satz, warum verworfen. `Verworfen.` / `Vorerst verworfen
  (revidierbar).`
```

Regeln zum Template:
- **Dateiname:** `adr-NNN-<kebab-title>.md`, NNN dreistellig (`001`), fortlaufend. Vor
  Vergabe der Nummer: `ls docs/adr/` -- hoechste vorhandene + 1.
- **ASCII only**, keine Unicode-Sonderzeichen (Projekt-Konvention).
- **Sprache Deutsch** (die bestehenden ADRs sind Deutsch).
- Nur die vier `##`-Abschnitte oben. Keine weiteren Ueberschriften erfinden.

## Status-Lifecycle

- Neue Entscheidung, noch nicht final beschlossen -> `Proposed`. Datum = heute.
- Beschlossen -> `Accepted`. (Beispiel ADR-002: "Proposed ... wird Accepted, sobald die
  OSS-Lizenz festgelegt ist" -- Bedingung fuer den Uebergang explizit machen.)
- Wird von einem neuen ADR abgeloest: altes ADR -> `Superseded`, `- Verwandt:` bzw. eine
  "abgeloest durch ADR-NNN"-Notiz; neues ADR nennt "loest ADR-MMM ab" (`supersedes`).
- `Rejected` = erwogen und verworfen (bleibt als Dokumentation stehen).
- `Deprecated` = ueberholt ohne konkreten Nachfolger.
- **Status nie ruecklos aendern** -- ein `Accepted` ADR wird nicht editiert, als waere die
  alte Entscheidung nie getroffen worden; es wird superseded.

## Cross-ADR-Konsistenz

Ein einzelnes gutes ADR reicht nicht -- das Korpus muss in sich stimmig sein. Beim Schreiben
eines neuen und beim Reviewen eines bestehenden ADR die *anderen* ADRs gegenlesen
(`ls docs/adr/`, dann lesen). Pruefe:

1. **Inhaltliche Widersprueche.** Trifft dieses ADR eine Entscheidung, die einem anderen
   `Accepted`/`Proposed` ADR widerspricht? Wenn ja: nicht stillschweigend beides
   stehenlassen -- den Konflikt benennen. Entweder loest das neue das alte ab (Superseded,
   siehe unten) oder einer der beiden ist falsch.
2. **Superseding sauber gepaart.** Loest dieses ADR ein aelteres ab? Dann: neues ADR nennt
   "loest ADR-MMM ab", altes ADR bekommt Status `Superseded` + Rueckverweis. Nie nur eine
   Seite. Keine verwaisten `supersededBy`-Verweise auf nicht existierende Nummern.
3. **Superseded/Deprecated ADRs sagen nichts mehr als gueltig.** Ein abgeloestes ADR bleibt
   als Historie stehen, darf aber nicht so klingen, als sei seine Entscheidung noch in Kraft
   -- Status-Zeile muss das klarstellen.
4. **`- Verwandt:`-Verweise sind beidseitig** und zeigen auf existierende Nummern. Nennt
   ADR-A "Verwandt: ADR-B", muss ADR-B auch ADR-A nennen.
5. **Keine Doppelung.** Zwei ADRs, die dieselbe Entscheidung treffen, sind ein Geruch --
   zusammenfuehren oder eines als `Superseded` markieren.

Bei einem gefundenen Widerspruch: melden, welche zwei ADRs kollidieren und worin, und einen
Aufloesungsvorschlag machen (ablosen / korrigieren / zusammenfuehren) -- nicht raten.

## Modus: Schreiben vs. Reviewen

**Neues ADR schreiben:**
1. Nummer bestimmen (`ls docs/adr/`).
2. Ist die Entscheidung wirklich getroffen? Wenn nein -> `Proposed`, Offenheit im Kontext
   benennen, NICHT erfinden.
3. Template fuellen, Litmus-Test auf jeden Satz anwenden.
4. Verwandte ADRs verlinken (und dort ggf. den Rueckverweis ergaenzen).
5. Cross-ADR-Konsistenz pruefen (siehe Abschnitt oben): widerspricht/abloest es ein
   bestehendes ADR? Wenn ja, die andere Seite mitpflegen.

**Bestehendes ADR reviewen (Checkliste):**
- [ ] Genau die vier Abschnitte, keine erfundenen (Offene Punkte / Umsetzung / Status)?
- [ ] Jeder Satz uebersteht den Litmus-Test (kein Schnappschuss, keine Commit-Ref)?
- [ ] Status + Datum gesetzt, genau ein Status?
- [ ] Enthaelt `## Konsequenzen` echte *Folgen* (nicht Wiederholung der Entscheidung)?
- [ ] Sind `## Alternativen` substanziell (nicht "keine erwogen")?
- [ ] Nummer eindeutig, Dateiname `adr-NNN-<kebab>.md`, ASCII, Deutsch?
- [ ] Querverweise beidseitig und auf existierende Nummern?
- [ ] Cross-ADR-Konsistenz: kein Widerspruch zu anderen ADRs, Superseding sauber gepaart,
      keine Doppelung (siehe Abschnitt "Cross-ADR-Konsistenz")?

Bei Verstoss: nicht kommentarlos umschreiben -- den Verstoss benennen (welche Fehlerklasse),
den Fix vorschlagen bzw. anwenden, und sagen, wohin der entfernte Inhalt gehoert.

## Referenz -- Gold-Beispiele

Die vorhandenen ADRs sind der Massstab. Lies sie, bevor du schreibst:

- `docs/adr/adr-001-local-client-and-swappable-store.md` -- vorbildlicher YAGNI-Konsequenz-
  Abschnitt (bewusst deferred *mit Begruendung*, kein Schnappschuss).
- `docs/adr/adr-002-open-core-editions.md` -- Status mit expliziter Uebergangsbedingung.
- `docs/adr/adr-003-adapter-b-remote-store.md` -- Richtungsentscheidung ("kein Code, bis X").
- `docs/adr/adr-004-spring-ai-mcp-tech-line.md` -- Tech-Linie gesetzt, saubere Alternativen.

Metamodell: `arknet-ontology/src/main/resources/arknet-architecture.ttl` (Abschnitt 4-6).
