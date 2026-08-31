# Architecture Decision Records

Handgepflegte, arknet-eigene Architekturentscheidungen (nicht das vom Nutzer modellierte
System -- siehe Root-`CLAUDE.md`, Abschnitt "Claude Code Plugin"). Nummernraum unabhaengig von
den store-first `adr_*`-Ressourcen der `arknet-adr`-BC.

**Die Statuszeile im jeweiligen Record ist massgeblich; diese Tabelle ist eine abgeleitete
Sicht.** Weichen beide voneinander ab, gewinnt der Record, und diese Tabelle wird korrigiert.

| #   | Titel                                                                  | Status                        | Datum      | Entscheidung |
|-----|-------------------------------------------------------------------------|--------------------------------|------------|--------------|
| 001 | [Lokaler Single-User-Client und austauschbarer Store](adr-001-local-client-and-swappable-store.md) | Proposed | 2026-07-13 | arknet ist ein lokaler Single-User-Client; der Store liegt hinter einem austauschbaren Out-Port. |
| 003 | [Adapter B -- remote Store-Backend](adr-003-adapter-b-remote-store.md) | Proposed | 2026-07-14 | Ein Remote-Store-Backend wird spaeter per MCP-Client an denselben Out-Port angebunden (Richtungsentscheidung, kein Code bis Adapter B ansteht). |
| 004 | [Spring AI 2.0 als Tech-Linie fuer den MCP-Layer](adr-004-spring-ai-mcp-tech-line.md) | Proposed | 2026-07-13 | Spring AI 2.0 ist die gesetzte Technologie-Linie fuer arknets MCP-Layer (Server und Client). |
| 005 | [Store-first -- das Modell lebt im Store](adr-005-store-first-model-lifecycle.md) | Proposed | 2026-07-14 | Der Store ist der primaere Ort des Modells; die Datei-Pipeline ist nicht mehr der primaere Modell-Lebenszyklus. |
| 006 | [Generischer Store-Lesepfad als Composition-Root-Werkzeug](adr-006-generic-store-read-path.md) | Accepted | 2026-07-14 | Der Store-Report ist kein eigener Bounded Context, sondern ein generischer Lesepfad (`store_overview`/`resource_get`) in der Composition Root. |
| 007 | [Geteiltes SHACL-Write-Gate als eigenes technisches Modul](adr-007-shared-shacl-write-gate-module.md) | Accepted | 2026-07-15 | Das SHACL-Write-Gate bekommt ein eigenes Modul (`arknet-persistence-support`), nicht den Shared Kernel. |
| 008 | [In-Adapter als Tor zum Bounded Context](adr-008-in-adapter-as-bc-gateway.md) | Accepted | 2026-07-17 | Ein In-Adapter darf den In-Port eines Nachbar-BC konsumieren -- er ist das Tor zum eigenen Hexagon, nicht Teil von dessen Core. |
| 009 | [MCP-Transport -- ein geteilter HTTP-Daemon](adr-009-mcp-http-daemon-transport.md) | Proposed | 2026-07-19 | `arknet-mcp` laeuft als ein geteilter HTTP-Daemon auf Loopback fuer alle Projekte, nicht als stdio-Subprozess pro Session. |
| 010 | [Review-UI als read-only Vaadin-OSS-Adapter](adr-010-review-ui-vaadin-oss-adapter.md) | Proposed | 2026-07-20 | Vaadin Flow ist die gesetzte Technologie-Linie fuer arknets Review-UI. |
| 011 | [Traceability ueber Commit-Provenance statt diffbarem Datei-Export](adr-011-commit-provenance-statt-diffbarem-export.md) | Accepted | 2026-07-26 | Es wird kein diffbarer Datei-Export gebaut; Datei-Ausgabe existiert nur als nicht-diffbarer Volldump. |
| 012 | [Plugin und Service in getrennten Repositories](adr-012-plugin-service-repository-split.md) | Accepted | 2026-07-25 | Plugin (`arknet-plugin`) und Service (`arknet`) leben in getrennten Repositories mit eigenen Versionsachsen statt einem Monorepo. |
| 013 | [Geteilter Schreibtrichter fuer die kognio-rdf-Out-Adapter](adr-013-shared-write-funnel.md) | Accepted | 2026-07-26 | Ein geteilter Schreibtrichter `WriteFunnel` in `arknet-persistence-support` buendelt die Schreibpfade der Out-Adapter. |
| 014 | [Revision als Concurrency-Token](adr-014-revision-als-concurrency-token.md) | Accepted | 2026-07-26 | Die Revision traegt eine Doppelrolle als PROV-O-Traeger und Concurrency-Token (Compare-and-Set ueber den Head). |
| 015 | [Domaenentypen bleiben Records](adr-015-domaenentypen-bleiben-records.md) | Accepted | 2026-07-27 | Die Domaenentypen der Bounded Contexts bleiben Records, kein graph-backed Domaenenobjekt. |
| 016 | [Projekt-Identitaet ueber registrierte Anker](adr-016-projekt-identitaet-ueber-registrierte-anker.md) | Accepted | 2026-07-28 | Projekt-Identitaet wird ueber registrierte, opake Anker aufgeloest statt aus dem Client-Verzeichnis abgeleitet. |
| 017 | [ISO/IEC/IEEE 15288 als Scope-Orientierung](adr-017-iso-15288-als-scope-orientierung.md) | Proposed | 2026-08-02 | Der Ontologie-Scope orientiert sich an 15288s Technical-Process-Gruppe als Rahmen, nicht als Implementierungsziel. |
| 018 | [Requirement-Status bleibt ohne Durchsetzung](adr-018-requirement-status-ohne-durchsetzung.md) | Superseded by ADR-019 | 2026-08-06 | Requirement-Status bleibt ein unverbindliches Reifegrad-Signal ohne Durchsetzung; Ausbau ist zurueckgestellt. |
| 019 | [Requirement-Status bleibt ohne Durchsetzung, ist aber beidseitig setzbar](adr-019-requirement-status-beidseitig-setzbar.md) | Accepted | 2026-08-06 | Requirement-Status bleibt ein unverbindliches Reifegrad-Signal ohne Durchsetzung, ist aber in beide Richtungen setzbar. |
| 020 | [Ein breit gefasster Actor statt eines eigenen Stakeholder-Typs](adr-020-actor-breit-statt-stakeholder-typ.md) | Proposed | 2026-08-31 | `arkproc:Actor` bezeichnet jede Instanz, die handeln oder Interessen haben kann; es gibt keinen Typ `Stakeholder`, Rollen entstehen aus Kanten. |
| 021 | [arkreq:Goal wird ersatzlos entfernt](adr-021-goal-ersatzlos-entfernt.md) | Accepted | 2026-08-18 | `arkreq:Goal` samt Properties und `motivatedBy` wird ersatzlos entfernt; eine spaetere Ziel-Ebene entstuende als eigener Schnitt. |
| 022 | [Actor wird ein eigener Bounded Context](adr-022-actor-als-eigener-bounded-context.md) | Proposed | 2026-08-31 | Actor bekommt einen eigenen Bounded Context mit eigenem Lebenszyklus; die Facette am Glossarbegriff entfaellt ersatzlos. |
| 023 | [Das Requirement bekommt eine Herkunftskante zum Actor](adr-023-requirement-herkunftskante-zum-actor.md) | Proposed | 2026-08-31 | Ein Requirement verweist auf den Actor, aus dessen Bedarf es stammt -- reine Verankerung, gemeldet statt erzwungen, kein Verhandlungsmodell. |

ADR-002 existiert nicht -- keine Luecke im Index, die Nummer wurde nie vergeben.
