# arknet-mcp

MCP-Server (Streamable HTTP, Spring AI 2.0 `spring-ai-starter-mcp-server-webmvc`) + Composition Root -- Spring Boot, verdrahtet requirements-Hexagon + ubiquitous-language-Hexagon + use-cases-Hexagon + bounded-context-Hexagon + adr-Hexagon + project-Hexagon als `@McpTool`-Beans.
Das project-Hexagon faellt dabei aus dem Muster der uebrigen fuenf: es verwaltet Identitaet statt Modell, seine Registry wohnt im reservierten System-Dataset (`ProjectId.RESERVED_SYSTEM_DATASET`) statt in einem Projekt-Dataset, und seine `@McpTool`-Beans bekommen darum weder eine `ProjectId` noch den `ProjectResolver` -- sie lesen den Anker des Aufrufs roh und schlagen ihn selbst nach (ADR-016).
Das ist keine Auslassung, sondern zwingend: dieser BC beantwortet die Routing-Frage fuer alle anderen und kann darum nicht selbst hinter einer Antwort darauf liegen.
Die BC-Hexagons teilen den `ProjectResolver`-Bean UND einen gemeinsamen `DatasetLifecycle`-Bean -- ein Dataset pro Projekt, aber die ProjectId wird pro Tool-Aufruf aufgeloest (nicht als Singleton injiziert), damit ein Prozess alle Projekte bedienen kann (ADR-009).
Dieser `DatasetLifecycle`-Bean ist mit `mcp/dataset/LockConflictReportingDatasetLifecycle` dekoriert: ein injiziertes `Predicate<RuntimeException>` (Default `KognioRdfRequirementRepositoryFactory.DEFAULT_LOCK_CONFLICT`, der einzige Ort ausserhalb der Adapter-Factories, der RDF4Js `RepositoryLockedException` kennt) entscheidet, ob ein fehlgeschlagenes `acquire` tatsaechlich ein Sperrkonflikt eines zweiten Prozesses auf demselben Storage-Verzeichnis ist; nur dann uebersetzt der Dekorator die Store-Exception in eine erklaerende `DatasetLockConflictException`.
Jeder andere `acquire`-Fehler -- Rechteproblem, voller Speicher, defekter Store -- laeuft unveraendert durch, damit er nicht faelschlich als Lock-Konflikt gemeldet wird.
`arknet-mcp` bleibt dadurch frei von direkten RDF4J-Imports.
Ergaenzend haelt `mcp/dataset/DaemonStorageLock` eine eigene, exklusive Dateisperre ueber dem gesamten Storage-Root, aufgenommen noch vor dem `datasetLifecycle`-Bean (per `@DependsOn` in der Bean-Reihenfolge davor erzwungen): ein zweiter Daemon-Prozess ueber demselben Storage-Root scheitert damit schon beim Start und beruehrt nie ein Projekt-Dataset, statt mit dem ersten Daemon um ein eben erst angelegtes Dataset zu wettlaufen -- die Sperre pro Dataset allein schliesst dieses Zeitfenster nicht, weil beide Prozesse dieselbe Neu-Anlage-Pruefung noch vor ihrer jeweiligen Dataset-Sperre auswerten.
`LockConflictReportingDatasetLifecycle` ist zugleich `AutoCloseable` (`destroyMethod = "close"` auf dem `datasetLifecycle`-Bean): beim Herunterfahren schliesst es jedes von `list()` gemeldete Dataset ueber den neutralen Port, statt das geordnete Schliessen dem Crash-Recovery des Stores zu ueberlassen.
Das ist best-effort, kein garantiertes Schliessen: haelt ein paralleler `acquire`-Aufruf zum Shutdown-Zeitpunkt noch eine offene Lease auf einem Dataset, bleibt es offen -- statt darauf zu warten oder den Shutdown zu blockieren, loggt der Dekorator dafuer nur eine Warnung, und genau dieses Dataset faellt auf Crash-Recovery zurueck.
Beherbergt zusaetzlich den generischen, BC-uebergreifenden Store-Lesepfad (`store_overview`/`resource_get`/`resource_history`, readOnly; ADR-006, `resource_history` issue #251) -- Logik in `mcp/store/`, kein eigener BC.
Der Rueckgabewert von `store_overview` (Agent-Digest) kommt aus genau diesem generischen `SELECT ?s ?p ?o`; der HTML-Report daneben nicht mehr: `mcp/report/` setzt ihn pro Bounded Context aus deren Lese-In-Ports zusammen (`ListBoundedContexts`/`ListRequirements`/`ListUseCases`/`ListTerms` plus `ResolveRequirements` fuer die Anzeige-Codes opaker Requirement-Referenzen -- geborgt nach ADR-008, wie `uc_get` es tut), weil ein aus Tripeln rekonstruierter Use Case kein lesbarer Use Case ist (Ablauf = `n` opake `arkreq:Step`-Subjekte, geordnet ueber ein `arkreq:position`-Literal).
Dieselbe ADR-008-Borrow-Rolle gilt fuer die Kopfzeile selbst: `StoreReportTools` fragt den neuen In-Port `FindProject` des Project-Hexagons je Aufruf ab und zeigt in Digest- und HTML-Kopfzeile das registrierte Label statt der rohen `ProjectId` -- mit Fallback auf die rohe id, wenn die Registry nichts liefert (ADR-016: jedes ueber `store_overview` erreichbare Projekt ist eigentlich bereits registriert, der Fallback ist also ein defensiver, kein regulaerer Pfad).
Traegt das aufgeloeste `Project` zusaetzlich eine (mehrsprachige, bereits ueber die injizierte `DisplayLocale` aufgeloeste) Beschreibung, zeigt sowohl der Text-Digest (`DigestRenderer`, eine `# <description>`-Zeile direkt unter der Kopfzeile) als auch der HTML-Report (`HtmlReportRenderer`, `<p class="project-desc">` direkt unter `<header class="top">`) sie an -- fehlt sie, bleibt beides unveraendert wie vor dieser Beschreibung (issue #110).
Beide Renderer nehmen dafuer ein zusaetzliches `Optional<String> description`-Argument neben dem bereits vorhandenen `label`.
Fuer Glossar-Referenzen borgt der Report **nicht** `ResolveTerms`, sondern liest ueber `ListTerms` einmal je Report das ganze Glossar in die `Glossary`-Projektion: sie labelt jede Term-Referenz (der Chip zeigt das `skos:prefLabel`, der Code `TERM-n` wandert in den Tooltip) UND findet dieselben Labels im Prosatext der anderen BCs wieder.
Die schmale Identitaets-Aufloesung von `ResolveTerms` traegt kein Label und kennt nur die Terms, auf die schon eine Kante zeigt -- fuer die zweite Frage ("nennt dieser Text einen Begriff, auf den *keine* Kante zeigt?") braucht es alle.
Requirement-Beschreibung/Akzeptanzkriterien und BC-Domain-Vision werden darum ausgezeichnet: eine Erwaehnung mit `arkreq:usesTerm`- bzw. `arkddd:ubiquitousLanguageTerm`-Kante wird zum Link, eine Erwaehnung ohne Kante zur sichtbaren Luecke (`Span.TermGap`) -- der Report behauptet damit keine Beziehung, die der Store nicht haelt, und macht die fehlende Kante auffindbar.
Der Abgleich ist absichtlich woertlich (case-insensitiv, Wortgrenze): deutsche Beugungen werden verfehlt, dafuer wird nie `Kundendienst` zur Erwaehnung von `Kunde`.
Die Chipliste unter dem Text schrumpft entsprechend auf die verlinkten Terms, die der Text *nicht* nennt.
Use-Case-Prosa bleibt unausgezeichnet: ein Use Case hat ausser den Aktorrollen keine Term-Kante, eine Luecke waere dort also nicht behebbar.
`ModelViews` haelt die fuenf Abschnitte und liest das Glossar als **eine** Quelle fuer alle (faellt dieser Lesevorgang aus, meldet er sich wie ein Abschnitt und der Rest steht weiter, nur ohne Labels und Auszeichnung).
Ein Render-neutrales Praesentationsmodell (`ModelCard`/`Block`/`Ref`/`RichText`+`Span`) trennt "welches Feld ist welche Form" (Karten-Builder) von "wie sieht eine Form aus" (`HtmlReportRenderer`) -- derselbe Schnitt, den ADR-010 fuer die Vaadin-Review-UI als zweiten Renderer braucht; welche Erwaehnung Link und welche Luecke ist, entscheidet damit der Builder, nicht der Renderer.
Im HTML ist jede Karte eingeklappt (`details.fold`, Kopf = Code/Titel/Badges), die Toolbar hat Expand-/Collapse-all, und das Skript klappt Sprungziele wieder auf -- sonst liefe jeder Referenz-Link auf eine zugeklappte Karte.
Der Snapshot bleibt Auffangnetz: je Karte die Roh-Tripel eine Ebene tiefer, und alles, was kein BC als Modellelement beansprucht, in einem eigenen generischen Abschnitt -- der Report kann nichts verbergen, was im Store steht.
Einzige strukturelle Ausnahme: von einem Use Case aus erreichbare `arkreq:Step`-Ressourcen erscheinen dort nicht doppelt, ein von keinem Use Case referenzierter Step dagegen schon.
Kein Abschnitt darf den Aufruf fallen lassen: ein werfender In-Port wird als sichtbare Warnung gemeldet, seine Ressourcen fallen in den generischen Abschnitt zurueck.
Daneben der Traceability-Lesepfad (`trace_matrix`/`orphan_check`/`impact_analysis`, readOnly) in `mcp/trace/`: `TraceabilityGraph` baut aus genau derselben `StoreReader#readSnapshot`-Momentaufnahme einen In-Memory-Graphen (kein zweiter, bespoke SPARQL-Pfad) und traversiert `arkreq:usesTerm`/`primaryActor`/`supportingActor`/`stepRealises`/`oslc_rm:constrainedBy` (issue #223, Requirement -> Constraint)/`arkddd:ubiquitousLanguageTerm`/`arkarch:addressesRequirement`/`arkarch:affectsContext`/`arkarch:supersedes` sowie den `mainStep`/`extensionStep`-Hop -- ein `arkreq:Step`-Knoten wird dabei durchquert (fuer den Hop zur besitzenden UseCase), aber nie selbst als Treffer gemeldet, da er ein aggregat-internes Value Object ohne eigene Identitaet ist.
`impact_analysis` teilt sich `resource_get`s Handle-Vertrag (CURIE/IRI/bare-id/Blank-Node-Referenz) ueber den dafuer aus `StoreReportTools` gezogenen `HandleResolver`, statt ihn zu duplizieren.
Die vierte Handle-Form -- eine Blank-Node-Referenz (`"_:" + label`, gerendert von `StoreReader#toNode`) -- adressiert eine store-first Ressource ohne gepraegte IRI (ADR-005): `HandleResolver` gibt sie unveraendert zurueck, und `StoreReader.outgoing`/`incoming` filtern dafuer `readSnapshot`s Ergebnis statt eine gezielte SPARQL-Query zu bauen, weil ein Blank-Node-Label im Query-Text laut SPARQL-Grammatik eine frische, query-scoped Variable ist und nie einen konkreten gespeicherten Knoten adressiert.
Von den ADR-Kanten sind bewusst nur diese drei dabei: `arkarch:supersededBy` schreibt niemand (die Rueckrichtung kommt aus einem Reverse-Read, siehe `arknet-adr/CLAUDE.md`), und `arkarch:relatedTo` bliebe als symmetrische "siehe auch"-Kante nicht abschliessbar -- jedes verwandte ADR waere von jedem anderen erreichbar und der Impact-Report ein Cluster-Dump.
Ergaenzt wurde nur die Rueckwaertsrichtung: `impact_analysis` IST der Rueckwaerts-Abschluss (`dependents`), und was ein ADR selbst referenziert, zeigt `adr_get`.
Der HTML-Report traegt daneben einen fuenften Abschnitt: `AdrCards` ("Architecture Decisions") baut ihn aus `ListAdrs`, das beide `supersedes`-Richtungen bereits aufgeloest liefert, und borgt `ResolveRequirements`/`ResolveBoundedContexts` (ADR-008), um `addressesRequirement`/`affectsContext` als Business-Codes zu zeigen -- genau wie `uc_get`/`adr_get` es bereits tun.
`orphan_check` traegt eine dritte Liste: Requirement-Text (`dcterms:description`/jedes verlinkten `arkreq:AcceptanceCriterion`s `arkreq:criterionText`, seit Issue #266 ein Zwei-Hop-Lesepfad ueber die `arkreq:acceptanceCriterion`-Kante) bzw. BC-Domain-Vision (`arkddd:domainVision`) gegen jedes Glossar-`skos:prefLabel` abgeglichen, gemeldet wird jede Erwaehnung ohne die dazugehoerige `usesTerm`-/`ubiquitousLanguageTerm`-Kante -- dieselbe Erwaehnung, die der HTML-Report seit dem Report-Umbau bereits als `Span.TermGap` zeichnet.
Eine vierte Liste (issue #223) meldet Constraints, die kein Requirement ueber `oslc_rm:constrainedBy` bindet -- `TraceabilityGraph#constraintIris`/`isConstraintReferenced` mirroren dafuer `requirementIris`/`isReferencedTerm`.
Dieselbe dritte Liste ("Mentioned in text but not linked") traegt zusaetzlich einen dritten Erwaehnungstyp, keine eigene Sektion: sie scannt jedes Glossar-Terms eigene `skos:definition` (`TraceabilityGraph#termProseTexts`) nach Erwaehnungen *anderer* Terme und meldet eine Erwaehnung nur, wenn sie nicht dem Term eigenem `skos:broader` entspricht (`TraceabilityGraph#broaderTerm`) -- ein Taxonomie-Term, der sein uebergeordnetes Element im Fliesstext nennt ("Ein Human Actor ist ein Actor, der ..."), ist kein Regelverstoss, jede andere unverlinkte Erwaehnung schon (`edgeLocalName` = `"broader"`).
Die Matching-Regeln (woertlich, case-insensitiv, Wortgrenze, links-nach-rechts-greedy ueber die Startposition -- bei gleichem Start gewinnt das laengere Label, bei unterschiedlichem Start und ueberlappenden Bereichen die frueher beginnende Erwaehnung, egal wie kurz) leben darum nicht mehr verdoppelt in `report/Glossary`, sondern einmal in der generischen `mcp/mention/LabelMentions<T>` -- `Glossary` labelt einen `Term`, `TraceabilityRenderer` eine blanke Term-IRI, keine der beiden Seiten kennt die andere.
Aus demselben Grund zaehlt `isReferencedTerm`/`dependents` `arkddd:ubiquitousLanguageTerm` seither als vollwertige Referenz: ein nur ueber einen Bounded Context verlinkter Term war zuvor ein falscher Treffer in "Terms never referenced" und unsichtbar fuer `impact_analysis`.
`isReferencedTerm` zaehlt zusaetzlich `skos:broader` als Referenz -- ein Term stellt sein "nie referenziert" ein, sobald ein anderer Term ihn als sein uebergeordnetes Element traegt, unabhaengig von jeder Requirement-/BC-/Actor-Kante.
Derselbe Lesepfad traegt zusaetzlich zwei rohe Strategic-Design-Werkzeuge (issue #108) -- bewusst ohne jede Cluster-Bildung oder Kontext-Grenzziehung, die bleibt einem Folge-Skill (`bc-audit`, arknet-plugin-Repo) ueberlassen: `actor_usecase_matrix` zeigt die bipartite Sicht auf `arkreq:primaryActor`/`supportingActor` in beiden Richtungen -- `TraceabilityGraph#actorsOf`/`#useCasesOf` sind die Vorwaertsabfragen zu genau den Kanten, die `DEPENDENT_EDGE_PREDICATES` fuer `dependents()` schon rueckwaerts traversiert.
`term_cooccurrence` liest reine Text-Kookkurrenz: welche Glossarbegriffe im selben Requirement- oder Use-Case-Text gemeinsam genannt werden, ueber dieselbe `LabelMentions`-Engine wie `orphan_check`, aber ohne Modellabgleich -- eine Kookkurrenz zaehlt unabhaengig davon, ob eine `usesTerm`-Kante existiert.
Als Use-Case-Prosa zaehlt dabei `arkreq:useCaseGoal` (`TraceabilityGraph#useCaseProseTexts`) -- das einzige Feld eines Use Case, das einer Beschreibung nahekommt; die Konstante (und `arkreq:UseCase` als Typ-IRI) wandert dafuer aus dem uc-Out-Adapter in die geteilte `ArkreqVocabulary`, analog zu den dort bereits geteilten `arkreq:`-Kanten.
`StoreReader` klammert **zwei** Infrastruktur-Graphen aus **allen drei** Lesepfaden aus; `readSnapshot`, `outgoing` und `incoming` teilen sich dafuer einen einzigen Filter-Baustein (`excludingInfrastructure` ueber die Liste `HIDDEN_GRAPHS`), damit der Ausschluss nicht zwischen Snapshot und Nachbarliste auseinanderlaufen kann.
Ausgeblendet sind der Provenance-Graph (`ArkprovVocabulary.PROVENANCE_GRAPH`, ADR-014) und die Selbstbeschreibung des Projekts (`ArkprjVocabulary.IDENTITY_GRAPH`, ADR-016 Punkt 7) -- Anker und Label, ueber die der Aufruf ueberhaupt hierher geroutet wurde.
Die Registry selbst braucht keinen Ausschluss: sie liegt in einem reservierten Dataset, das kein gewoehnlicher Aufruf adressiert, waehrend die Selbstbeschreibung per Konstruktion IM gelesenen Dataset liegt.
`store_overview`/`resource_get` zeigen weiterhin das Modell, nie seine Aenderungshistorie -- der Trail waechst mit jedem Write, und jede Revision zeigt via `prov:specializationOf` auf ihre Ressource.
Auch der `arkprov:head`-Zeiger bleibt dort aussen vor, obwohl genau einer je Ressource begrenzt und billig zu zeigen waere und obwohl er seit ADR-014 Entscheidung 4 ein nutzbares Concurrency-Token ist -- alle vier vormals umgangenen Pfade (`req_update`/`req_set_status`/`req_link_term`/`term_update`) bewegen ihn jetzt, da sie durch `WriteFunnel#compareAndUpdate` laufen.
Die frueher offene Frage, ob und wie die Historie ueberhaupt sichtbar wird, ist mit issue #251 fuer den Trail selbst beantwortet, nicht durch einen im Modell-Lesepfad gerenderten Head: `resource_history` (`StoreReportTools`) ist ein eigenes, bewusst separates Tool, das `StoreReader#history` direkt gegen `ArkprovVocabulary#PROVENANCE_GRAPH` fragt -- die eine Ausnahme von `StoreReader`s Infrastruktur-Ausschluss, keine Aufweichung davon; `store_overview`/`resource_get` bleiben unveraendert blind fuer diesen Graphen.
Zurueckgegeben wird je Ressource die Liste ihrer `arkprov:Revision`s, aeltest zuerst, mit der Revision markiert, deren IRI dem aktuell gelesenen `arkprov:head` entspricht; eine Ressource, die nie durch den Trichter geschrieben wurde (store-first, ADR-005, oder aelter als der Trichter), hat eine leere, aber fehlerfreie Historie -- `resource_history` prueft Existenz separat ueber `outgoing`/`incoming`, genau wie `resource_get` es fuer sein eigenes "nicht gefunden" tut.
`Prefixes.defaults()` traegt seitdem genau eine Revisions-Bindung (`rev:` -> `ArkprovVocabulary#REVISION_IRI_BASE`, dafuer aus einer vormals `WriteFunnel`-privaten Konstante in die geteilte Vokabularklasse gehoben), aber weiterhin keine `arkprov:`/`prov:`-Praedikat-Bindungen -- `resource_history` rendert eine kuratierte Sicht (laufende Nummer, Zeitstempel, current-Markierung), keine rohen Tripel, und braucht sie darum nicht.
`TraceabilityMcpTools#readGraph` loest seit issue #274 nicht mehr die prozessweite, injizierte `DisplayLocale`-Bean unveraendert auf, sondern mischt je Aufruf die `defaultLanguage` des ueber den Anker aufgeloesten Projekts ein (`DisplayLocale#withRequestedOverride`, `AnchorContext#resolveResolvedProject`) -- ohne das waehlte `LabelMentions`s Suchmuster fuer einen mehrsprachigen Term dieselbe Sprache wie der Daemon-Default statt die des Projekts, wodurch eine woertliche Erwaehnung in der jeweils anderen Sprache unentdeckt blieb (derselbe Mechanismus, den `term_get` schon seit `effectiveDisplayLocale` beherrscht).
`store_overview`/`resource_get` (`StoreReportTools`) ziehen seit issue #276 denselben Merge nach: `DigestRenderer`/`HtmlReportRenderer` backen die Locale nicht mehr in ihren Konstruktor, `StoreReportTools` loest sie stattdessen je Aufruf ueber `AnchorContext#resolveResolvedProject` auf und mischt sie per `withRequestedOverride` ein, `ModelViews#of` faedelt dieselbe gemergte Sprache zusaetzlich in `ListTerms#list` -- der Glossar-Abschnitt widerspricht `orphan_check`/`term_get` seither nicht mehr beim Label desselben Terms.
Dieselbe Luecke eine Ebene tiefer ist mit issue #281 geschlossen: `ListRequirements#list`/`ListUseCases#list` nehmen seither einen `displayLocale`-Parameter entgegen und wenden dieselbe Fallback-Kette an wie `req_get`/`uc_get` (issue #229) -- `RequirementCards`/`UseCaseCards` im HTML-Report ziehen den gemergten Wert ueber `ModelViews#of` nach, siehe issue #276 oben.

Betriebsmodell: EIN geteilter, langlebiger Daemon fuer alle Projekte der Maschine auf
`127.0.0.1:47331` (Loopback-only, daher ohne Authentifizierung) statt eines stdio-Subprozesses pro
Claude-Code-Session -- Grund: mehrere Sessions/Worktrees desselben Projekts teilen einen Store und
kollidierten als eigene Subprozesse am NativeStore-Verzeichnis-Lock. Die Loopback-Grenze ist
durchgesetzt, nicht nur behauptet: `AnchorHttpTransportConfiguration` setzt auf dem
`WebMvcStreamableServerTransportProvider`-Bean einen `DefaultServerTransportSecurityValidator`
mit einer Host-Allowlist aus `127.0.0.1:*`/`localhost:*` -- Spring AI MCPs Default waere
sonst `ServerTransportSecurityValidator.NOOP`, der den Origin-/Host-Check aufruft, aber nichts
prueft, und liesse einen per DNS-Rebinding same-origin gemachten Request durch (ADR-009 Punkt 4).
Der Port bleibt in der Allowlist bewusst als Wildcard offen statt auf den `application.properties`-
Default `47331` gepinnt: `server.port` ist ueber `arknet.mcp.port` selbst konfigurierbar, und eine
auf den Default gepinnte Allowlist haette jeden Aufruf gegen einen ueberschriebenen Port mit einem
421 abgelehnt, das weder Ursache noch Abhilfe nennt (issue #295) -- die DNS-Rebinding-Abwehr haengt
am Hostnamen, nicht am Port, der Wildcard ist also sicherheitsaequivalent.

Welches Projekt ein Aufruf trifft, entscheidet der **Anker**: eine opake Zeichenkette, die der
Client mitschickt und die der Server ausschliesslich nachschlaegt (ADR-016). Sie kommt pro Aufruf
aus dem `.mcp.json`-Header `X-Arknet-Project-Anchor: ${PWD}` --
`AnchorHttpTransportConfiguration` ueberschreibt den Spring-AI-Transport-Provider mit einem
`contextExtractor`, der den Header unter `ProjectResolver.ANCHOR_KEY` in den
`McpTransportContext` legt; die `*McpTools` lesen ihn dort pro Call und loesen ihn ueber den
`ProjectResolver`-Bean (`RegisteredAnchorProjectResolver` -> `ResolveProject` des
`arknet-project`-BC) auf. Alternativ nimmt **jedes** Tool einen optionalen
`projectAnchor`-Parameter, damit ein Client ohne Header-Kontrolle nicht ausgesperrt ist (ADR-016
Punkt 2); der Header bleibt Primaerweg, weil ein per Parameter uebergebener Anker vom Sprachmodell
stammt und ein geratener, aber zufaellig existierender still das falsche Projekt traefe.
Fehlender oder unbekannter Anker ist ein Fehler mit nach Aufrufstelle getrennter Meldung -- **kein**
Default, **kein** Rueckfall auf das Daemon-Arbeitsverzeichnis (ADR-016 Punkt 3). Der Header ist
Projekt-Routing, keine Authentifizierung (ADR-009). Weil das Projekt pro Aufruf aus dem Anker kommt,
genuegt ein Port fuer alle Projekte. Ein HTTP-Eintrag in `.mcp.json` ist bei Claude Code rein passiv
(nur Verbindungsaufbau, kein Prozess-Spawn/-Management) -- Start und Betrieb des Daemons sind Sache
des Menschen, siehe `README.md`.

`RegisteredAnchorProjectResolver` ist bewusst **ohne Cache**: die Aufloesung ist ein lokaler
Store-Read auf ein Subjekt, das die Registry direkt indiziert -- billig genug, dass ein Cache wenig
brauechte und Korrektheit genau an der Kante koestete, die am meisten zaehlt. Ein eben angelegtes
oder adoptiertes Projekt muss beim naechsten Aufruf aufloesen, sonst schiene der Ausweg, den die
Fehlermeldung gerade genannt hat, nicht zu funktionieren. (Ein Staleness-Fenster lohnt sich nur
gegen Kosten in der Groessenordnung eines Prozess-Starts, nicht gegen einen indizierten Read.)

Der HTML-Report von `store_overview` landet **immer** im serverseitigen
`arknet.report.dir/<projectId>`, nie im Verzeichnis des Clients: der Header traegt einen Anker,
keinen Pfad, und ihn an `Path.of` zu reichen waere genau die Interpretation, die ADR-016
ausschliesst -- fuer einen `url`/`uuid`-Anker ginge sie ohnehin schief. Im containerisierten
Betrieb ist es der einzige erreichbare Ort, weil das Verzeichnis des Clients im Container nicht
existiert. Der projekt-scoped Unterordner ist damit das einzige, was zwei Projekte im
gemeinsamen Report-Verzeichnis auseinanderhaelt.

Die kompakte Kartenansicht zeigte bis issue #270 (Teil 2 von #248) nur die zum Report-Zeitpunkt
per `DisplayLocale` aufgeloeste Sprache eines mehrsprachigen Feldes, obwohl jede Sprachvariante
schon in der Rohtripel-Ansicht der eigenen Karte lag. `HtmlReportRenderer#languageVariants`
schliesst diese Luecke rein client-seitig, ohne neue Domain-/Port-Plumbing: sie matcht den
gerade angezeigten Text eines Kartentitels oder `Block.Prose`-Feldes per Text-Gleichheit gegen
die Literale der Karten-eigenen `raw`-`StoreResource` zurueck auf ein Praedikat -- traegt
`subject` mehr als ein Praedikat mit demselben Text, gilt der Rueckmatch als mehrdeutig und
bleibt ohne Switch, statt am falschen Feld zu raten. Ein ungetaggtes Literal (Altbestand vor
issue #258, oder `DisplayLocale`s Fallback-Stufe 3) zaehlt als Kandidat unter
`displayLocale.systemDefault()`. Gefundene Varianten rendert `langSwitchable` als
`.lang-variant`-Spans in einer `.lang-group`-Huelle (nur die aktive Sprache sichtbar), ein
Toolbar-`<select>` plus IIFE-Script scannt die `data-lang`-Attribute des Dokuments und schaltet
um, mit Fallback auf die Default-Sprache eines Feldes, falls die gewaehlte Sprache dort keine
Variante hat. Bewusst ausgespart: `Block.Bullets`/`Block.Flow` (Use-Case-Extensions,
Flow-Step-Text) -- die haengen an eigenen opaken `arkreq:Step`-Sub-Ressourcen statt an der
Karten-eigenen `raw`-Tabelle und brauchten dafuer zusaetzliches Threading, das dieses Issue nicht
umfasste.
