# arknet-adr

Sechste hexagonale BC (Bauart 1:1 zu bounded-context) -- arknet-adr-core + arknet-adr-adapter-kogniordf (Out) + arknet-adr-adapter-mcp (In).
Macht `arkarch:ArchitectureDecisionRecord` store-first mintbar (`adr_add`/`adr_list`/`adr_get`/`adr_update`/`adr_set_status`/`adr_supersede`, #69) -- der Anlass ist ADR-005: ADRs waren die letzte Artefaktklasse mit datei-basiertem Lebenszyklus, ausgerechnet die, fuer die arknet das Vokabular laengst mitbrachte. **Die 15 Markdown-ADRs unter `docs/adr/` sind explizit nicht Teil davon**: keine Migration, keine Koexistenz-Logik, kein gemeinsamer Nummernraum -- der Store-Code laeuft ungepolstert `ADR-1`, `ADR-2`, ..., je Projekt, die Dateien zero-padded `adr-NNN-*.md`; `AdrCode`s Javadoc sagt das, und die `adr_add`-Tool-Beschreibung sagt es dem Aufrufer, damit niemand die beiden Raeume verwechselt.
Ob und wann migriert wird, ist eine eigene, nachgelagerte Entscheidung (Klaerung zu #69).

**Ontologie war die Vorarbeit, nicht der Bau.** `arkarch:` existierte vollstaendig, lag aber unter `parked/` (kein lebender Konsument, `arknet-ontology/CLAUDE.md`).
Der Split folgt exakt dem `arkddd#`-Praezedenzfall aus PR #57: die ADR-Sektionen (Klasse, Datatype-Properties, Relationen, die fuenf Status-Individuen, die zwei OWL-Restriktionen) wandern in ein aktives `arknet-architecture.ttl`, der ISO-42010-Rest (Architecture, ArchitectureDescription, Stakeholder, Concern, Viewpoint, View) bleibt geparkt als `parked/arknet-architecture_parked.ttl` -- gleicher Namespace, gleicher Ontologie-IRI, `_parked`-Suffix wie bei `arknet-ddd_parked.ttl`, weil der Dateiname sonst kollidiert.
Dieselbe Teilung fuer die Shapes: aktiv `architecture-shapes.ttl` (nur `ashapes:ADRShape` + seine sieben Property-Shapes), geparkt `parked/architecture-shapes_parked.ttl`.
Die `sh:in`-Liste von `ashapes:ADR-status` bleibt unangetastet fuenfwertig.

**Opake Identitaet:** `AdrId` wrapt eine vom Kernel gemintete `ResourceId`, `AdrCode` traegt die `ADR-N`-Semantik als reines Business-Label (`dcterms:identifier`) -- der MCP-Nutzer tippt den Code.
`AdrId` ist zugleich der Referenztyp der selbstbezueglichen `supersedes`-Kante: ein abgeloestes ADR ist eine Ressource **dieses** Hexagons, also ist dessen eigener Identitaetstyp der ehrliche, und ein separates `AdrRef` verdient sich seinen Platz nicht (anders als `RequirementRef`/`BoundedContextRef`, die nackte `ResourceId`s halten, gerade weil ihr Ziel im Nachbar-Hexagon liegt).

**Drei Relationen, drei verschiedene Aufloesungsmuster** -- hier ist die BC echt komplexer als ihre Vorlage:

1. `arkarch:supersedes` (ADR -> ADR, selbstbezueglich).
Kein neuer Out-Port: `AdrService#supersede` loest den Ziel-Code ueber `AdrRepository#findByCode` im eigenen Hexagon auf, ein unbekannter ist eine gewoehnliche `AdrNotFoundException` statt einer didaktischen Cross-BC-Ablehnung. **Nur die Vorwaertskante wird als Tripel geschrieben.** Das ontologische `owl:inverseOf`-Gegenstueck `arkarch:supersededBy` bleibt bewusst unmaterialisiert -- nichts in diesem Code reasont ueber Inverse (der Gate reasont nicht, s. bounded-context), und zwei handgepflegte Tripel sind genau das Drift-Risiko, das dieses Projekt sonst meidet.
Die Rueckrichtung kommt aus einem Reverse-Read (`AdrRepository#findSupersedingCodes`), derselben Rueckwaerts-Traversierung wie `TraceabilityGraph#dependents`.
Ein `KognioRdfAdrRepositoryTest` nagelt fest, dass `supersededBy` nach einem `adr_supersede` **nicht** im Graphen steht.
2. `arkarch:addressesRequirement` (ADR -> `arkreq:Requirement`, Cross-BC).
Schreibseite: eigener Out-Port `RequirementLookup` + `KognioRdfRequirementLookup` (Code -> `ResourceId` per `dcterms:identifier`, didaktische Ablehnung ueber `UnresolvedReferenceException`), Bauart 1:1 zum gleichnamigen uc-Adapter.
Leseseite: der **bestehende** req-In-Port `ResolveRequirements`, konsumiert vom In-Adapter (ADR-008) -- kein zweiter Mechanismus fuer dieselbe Richtung.
3. `arkarch:affectsContext` (ADR -> `arkddd:BoundedContext`, Cross-BC).
Schreibseite analog (`BoundedContextLookup` + `KognioRdfBoundedContextLookup`).
Leseseite: der bc-BC hatte noch **keinen** `ResolveBoundedContexts`-In-Port -- dieser Bau zieht ihn nach, Form 1:1 zu `ResolveTerms`/`ResolveRequirements` (`resolveExisting(ProjectId, ResourceId...)` -> `List<ResolvedBoundedContext>`, wirft nie, Batch nach Identitaet), getragen vom neuen Out-Port `BoundedContextRepository#findByIds` (`VALUES`-gebundener SPARQL-Batch, Join nur ueber `dcterms:identifier`, damit ein store-first BC ohne `name`/`domainVision` trotzdem anzeigbar bleibt). *Namens-Drift benannt statt stillschweigend entschieden:* `ResolveTerms` heisst seine Methode `getById`, `ResolveRequirements` `resolveExisting` -- zwei Elemente, zwei Namen.
Der neue folgt dem juengeren (`resolveExisting`); die Vereinheitlichung der bestehenden zwei ist bewusst nicht Teil dieses Baus.

Alle drei Kanten stecken **im** `Adr`-Record, nicht daneben -- sonst loescht der naechste replace-by-identity-Write sie still (Lehre aus req/bc).

**Schreibpfad von Anfang an mit CAS.** `AdrRepository` traegt `create` + `compareAndUpdate` und **keine** unbedingte Update-Methode; `AdrService#accept`/`#supersede` laufen ueber `updateWithOptimisticRetry` (Kernfelder+`arkprov:head` aus `findCurrentByCode`, Mutation, `compareAndUpdate` mit dem beobachteten Head, Retry gegen einen frischen Read, `AdrConcurrentlyModifiedException` bei Erschoepfung).
Das ist die Nachruestung, die req und bc je nachtraeglich brauchten -- hier ab dem ersten Commit.
`add()` vergibt den naechsten `ADR-N`-Code ueber den geteilten `CodeAssignment`-Retry.
Die Cross-BC-Aufloesung sitzt bewusst **vor** dem Retry: ein unbekanntes `FR-9` ist keine Code-Kollision.
Der Head schuetzt Trichter-Schreiber gegeneinander, nicht gegen store-first-Edits am Trichter vorbei (ADR-014-Nachtrag).

**Status-Teilmenge.** `AdrStatus` implementiert vier der fuenf Ontologie-Werte: `PROPOSED`, `ACCEPTED`, `REJECTED`, `DEPRECATED`.
Legale Transitionen: `PROPOSED -> ACCEPTED`/`REJECTED` (`Adr#accept()`/`Adr#reject()`), und `ACCEPTED -> DEPRECATED` (`Adr#deprecate()`) fuer eine obsolete Entscheidung ohne Nachfolger.
Keiner der drei Werte laesst sich in einen der anderen zurueckfuehren -- das ist ein bewusst kleiner, terminal-lastiger Ausschnitt, keine vollstaendige State Machine.
Je Transition ein eigener In-Port (`AcceptAdr`/`RejectAdr`/`DeprecateAdr`), keiner nimmt einen Zielstatus -- exakt der Schnitt, den die requirements-BC fuer `AcceptRequirement` gezogen hat; das Tool bleibt `adr_set_status` und validiert seinen `status`-Parameter gegen `ACCEPTED`/`REJECTED`/`DEPRECATED`.
Die Transitionsregeln selbst leben auf `Adr#accept()`/`#reject()`/`#deprecate()`, nicht im Service; `AdrService` reicht jede unveraendert an `updateWithOptimisticRetry` durch, denselben CAS-Helfer, den `accept`/`supersede` bereits nutzen.
`SUPERSEDED` bleibt bewusst **kein** Statuswert: es blieb offen, ob die fuenfte Ontologie-Individuum zusaetzlich als Status geschrieben wird oder ausschliesslich aus der `supersedes`/`supersededBy`-Relation abgeleitet bleibt -- entschieden fuer Letzteres, aus derselben Begruendung, die `supersededBy` selbst nie als zweites Tripel materialisiert: zwei unabhaengig gepflegte Signale fuer denselben Sachverhalt sind genau das Drift-Risiko, das dieses Projekt meidet.
`adr_supersede` laesst den Status des abgeloesten ADR entsprechend weiterhin unberuehrt.

**Korrektur ist gestaffelt: Text nur solange `PROPOSED`, Kanten immer** (`adr_update`, `UpdateAdr`/`AdrCorrection`).
Die sechs Textfelder (`name`/`context`/`decision`/`consequences`/`alternatives`/`decisionDate`) sind ab `ACCEPTED` -- und ebenso ab `REJECTED`/`DEPRECATED` -- nicht mehr aenderbar: eine in Kraft gesetzte Entscheidung protokolliert, was damals entschieden wurde, und die Korrektur laeuft ueber einen Nachfolger statt ueber einen Edit (Nygard).
`AdrTextImmutableException` sagt das dem Aufrufer und verweist auf `adr_supersede`, statt nur zu blocken.
`arkarch:addressesRequirement` und `arkarch:affectsContext` sind die bewusste Ausnahme und bleiben in **jedem** Status korrigierbar: eine spaeter ergaenzte Kante vervollstaendigt einen Verweis, den es zur Schreibzeit nicht geben konnte, weil der Ziel-BC noch nicht existierte -- dieselbe Lizenz, die sich `adr_supersede` gegen ein `ACCEPTED`-ADR laengst nimmt.
Beide Regeln leben auf `Adr#reviseText()`/`Adr#reviseReferences()`, nicht im Service und **nicht** in `architecture-shapes.ttl`: eine Shape validiert einen Graph-Zustand, keinen Uebergang, "dieser Text darf sich nicht geaendert haben" ist dort gar nicht ausdrueckbar.
`reviseText` vergleicht **zuerst** feldweise und prueft **danach** den Status -- eine Aenderung, die nichts aendert, ist in jedem Status ein No-op statt einer Ablehnung. Das ist load-bearing, weil ein reiner Kanten-Update denselben Pfad nimmt; und ein ungueltiger Wert an einem `ACCEPTED`-Record scheitert so an der Unveraenderlichkeit, dem eigentlichen Grund, statt an der Validierung.
Gepatcht wird feldweise: `null` heisst "unveraendert lassen" und ist nie ein Loesch-Signal.
Fuer die beiden Kanten-Listen ist der Unterschied `null`/leer dagegen bedeutungstragend (`null` = unveraendert, leere Liste = alle Kanten entfernen, nicht-leere Liste = Wholesale-Ersatz), darum normalisiert `AdrCorrection`s Compact Constructor `null` als einziger im BC **nicht** auf `List.of()`.
Die Referenz-Codes loest `AdrService#update` wie `#add` **vor** der Retry-Schleife auf; der Rest laeuft ueber denselben `updateWithOptimisticRetry`-CAS-Helfer.

**`AdrDetail` statt nacktem `Adr` an jedem In-Port.** Jeder Driving-Port liefert `AdrDetail(Adr, List<AdrCode> supersedes, List<AdrCode> supersededBy)`.
Grund: die `supersedes`-Identitaeten sind opak, ihre Anzeige-Codes gehoeren diesem Hexagon (kein ADR-008-Borrow noetig), und die Rueckrichtung ist ueberhaupt nur per Reverse-Read zu haben.
`adr_list` leitet **beide** Richtungen aus seinem einen `findAll` in-memory ab (kein Reverse-Query je Zeile); die Einzel-ADR-Pfade zahlen dafuer zwei billige Reads (`findCodesByIds` + `findSupersedingCodes`).

**Out-Adapter.** Ein Named Graph `https://w3id.org/arknet/model/adr`; alle Praedikat-/Typ-/Individuen-IRIs aus `ArkarchVocabulary` (`arknet-persistence-support`).
Anders als `ArkreqVocabulary`/`ArkdddVocabulary`, deren Scope bewusst nur die modul-uebergreifend duplizierten Praedikate umfasst, spiegelt `ArkarchVocabulary` sein **ganzes** (ADR-only) Ontologie-Modul -- die Bauart von `ArkprovVocabulary`/`ArkprjVocabulary`, und erst sie macht den beidseitigen Abgleich `ArchitectureVocabularyMatchesOntologyTest` (arknet-architecture-tests) moeglich.
`replaceTriples` bewahrt ueber den replace-by-identity-Write hinweg: **alle** `arkarch:supersededBy`- und `arkarch:relatedTo`-Kanten (kein Domain-Feld, nur store-first erreichbar -- dieselbe Rolle wie `arkddd:hasAggregate` bei bc) sowie Nicht-IRI-Ziele von `addressesRequirement`/`affectsContext`/`supersedes` (Blank Nodes, die `ResourceId` nicht darstellen kann, dieselbe Bewahrungslogik wie bei den anderen BCs).
Beide Faelle mit Regressionstest.
Die Lese-Pfade gruppieren pro Subject und waehlen deterministisch den zuerst gesehenen Wert mit `WARN` bei kollabierten Mehrwerten: ausser `dcterms:identifier` und `adrStatus` traegt keine ADR-Property-Shape ein durchsetzbares `sh:maxCount`.
`FILTER(isIRI(?s))` schuetzt gegen ein store-first Blank-Node-Subject.
`arkarch:decisionDate` traegt gar keine Property-Shape -- ein unparsbares Literal wird mit `WARN` uebersprungen statt den ganzen Read zu reissen.
Das Gate laedt `/architecture-shapes.ttl`, targetet `arkarch:ArchitectureDecisionRecord` direkt und braucht darum weder Axiome noch Shape-Filterung (wie bc, anders als req/uc).
Ein `sh:class`-Constraint auf den Referenz-Praedikaten gibt es nicht, also auch keinen validation-only `assertedContext`.

**Traceability.** `impact_analysis` (arknet-mcp) folgt die drei ADR-Kanten rueckwaerts mit: Requirement aendern -> das adressierende ADR ist betroffen, BoundedContext aendern -> das betreffende ADR, ADR abloesen -> sein Nachfolger.
`arkarch:relatedTo` bleibt bewusst draussen -- eine symmetrische "siehe auch"-Kante machte jedes verwandte ADR von jedem anderen erreichbar und den Impact-Report zum Cluster-Dump.
`arkarch:supersededBy` bleibt draussen, weil es niemand schreibt.
Vorwaertsrichtung ist bewusst **nicht** ergaenzt: `impact_analysis` ist definitionsgemaess der Rueckwaerts-Abschluss (`dependents`), und was ein ADR selbst referenziert, zeigt `adr_get` ohnehin.
Kein `adr_impact`.
Der HTML-Report traegt einen eigenen "Architecture Decisions"-Abschnitt (`AdrCards` in arknet-mcp, siehe dort).
