# ADR-006: Generischer Store-Lesepfad als Composition-Root-Werkzeug, nicht als Bounded Context

- Status: Accepted (2026-07-14)
- Verwandt: ADR-001 (austauschbarer Store), ADR-005 (Store-first), ADR-010 (Review-UI --
  konsumiert diesen Lesepfad in-process), ADR-016 (Projekt-Identitaet -- dessen
  Registry-Dataset muss dieser Lesepfad ausblenden, wie er es mit dem Provenance-Graphen tut)

## Kontext

Mit ADR-005 ist der Store der primaere Modell-Ort; die Bounded Contexts (requirements,
ubiquitous-language) schreiben ueber domaennahe Write-Tools. Zum Lesen fehlte ein Weg, der
zeigt, WAS ueberhaupt im Workspace-Store steht -- domaenenuebergreifend, ohne fuer jeden Typ
ein eigenes Tool zu bauen. Ein Agent braucht einen billigen Ueberblick plus einen Drill-down
auf eine einzelne Ressource; ein Mensch braucht eine navigierbare Ansicht.

Zu entscheiden war: (a) Wo lebt dieser Lesepfad -- eigener BC, eigenes Modul, oder im
Composition Root? (b) Wie kommt er an den Store, ohne die Reinheit der Adapter-Schnitte zu
verletzen? (c) Wie lautet der Ressourcen-Handle-Vertrag?

## Entscheidung

1. **Kein eigener Bounded Context, kein eigenes Hexagon-Modul.** Der Store-Report hat keine
   Domaene -- er ist ein generischer, technischer Lesepfad ueber das, was die BCs geschrieben
   haben. Er lebt im Modul `arknet-mcp` (Composition Root) als zwei readOnly-`@McpTool`s
   (`store_overview`, `resource_get`). Die Logik (CURIE/IRI-Aufloesung, Snapshot-Aggregation,
   Digest-/HTML-/Resource-Rendering, Query-Ausfuehrung) liegt in isoliert unit-testbaren
   Klassen im Paket `de.hauschel.arknet.mcp.store`; `ArknetMcpConfiguration` bleibt reines
   Bean-Wiring.

2. **Eine generische `SELECT ?s ?p ?o`-Query.** Beide Tools speisen sich aus genau einem
   generischen Statement-Read (`StoreReader`) ueber Default- und Named-Graphs. Kein
   Typ-zu-Tool-Mapping -- derselbe Code bedient requirements, ubiquitous-language und jeden
   kuenftigen BC gleich.

3. **Ein geteilter `DatasetLifecycle`-Bean.** Der persistente Lifecycle wird einmal gebaut
   (Fabrikmethode `KognioRdfRequirementRepositoryFactory.persistentLifecycle(Path)`, die nur
   den technologieneutralen `DatasetLifecycle` zurueckgibt) und an alle Store-Konsumenten
   gereicht: requirement- und term-Repository (ueber ihre `over(DatasetLifecycle)`-Fabriken)
   sowie den Store-Report. So laufen alle gegen denselben Store statt je Factory einen eigenen
   `DatasetLifecycleRdf4j` auf dasselbe Verzeichnis (Lock-Risiko). `arknet-mcp` zieht dafuer
   nur die neutralen kognio-rdf-Ports (`rdf-terms`, `rdf-dataset`) -- die RDF4J-Bindung bleibt
   in den Adapter-Modulen gekapselt.

4. **Handle-Vertrag fuer `resource_get`.** Verbindlich ist CURIE (`req:FR-1`) oder volle IRI;
   zusaetzlich wird eine blanke Business-Id (`FR-1`) als Komfort gegen `dcterms:identifier`
   aufgeloest. Unbekannter Prefix oder eine ueber BCs hinweg mehrdeutige Id werden mit einer
   didaktischen Meldung abgelehnt statt geraten.

## Konsequenzen

**Positiv:**

- Ein Lesepfad fuer alle BCs; neue BCs erscheinen ohne Report-Aenderung.
- Der geteilte Lifecycle beseitigt das Mehrfach-Lock auf `~/.arknet/rdf`.
- Composition-Root-Reinheit bleibt: die RDF4J-Technologie steckt weiter nur in den
  `*-adapter-kogniordf`-Modulen; der Report nutzt ausschliesslich neutrale Ports.

**Negativ / bewusst:**

- Die Fabrikmethode fuer den geteilten Lifecycle haengt physisch am requirements-Adapter
  (`KognioRdfRequirementRepositoryFactory`), obwohl der Lifecycle BC-neutral ist. Bewusst in
  Kauf genommen (kein neues Infra-Modul fuer eine Methode); ein spaeteres Herausziehen in ein
  neutrales kognio-Wiring-Modul bleibt moeglich.
- Anzeige-Heuristiken (Label-/Status-/Prio-Erkennung, Primaertyp = alphabetisch kleinste
  Typ-IRI) sind Praesentations-Konventionen im Report, keine Domaenenregel -- eine Ressource
  ohne diese Praedikate rendert weiterhin, nur mit weniger Hinweisen.

## Alternativen

- **Eigener BC / eigenes Modul fuer den Report.** Verworfen: kein fachlicher Besitz, keine
  Domaene -- das waere ein Hexagon ohne Kern. Der Report ist Orchestrierung/Technik.
- **Pro Typ ein eigenes Lese-Tool.** Verworfen: skaliert nicht, koppelt den Lesepfad an jede
  Domaenenerweiterung und widerspricht dem generischen Anspruch.
- **Je Factory ein eigener Lifecycle (Status quo vor #47).** Verworfen: zwei
  `DatasetLifecycleRdf4j` auf dasselbe Verzeichnis -- Lock-Risiko.

## Nachtrag 2026-07-18 (#131): Traceability-Lesepfad (`trace_matrix`/`orphan_check`/`impact_analysis`)

Drei weitere readOnly-`@McpTool`s (`de.hauschel.arknet.mcp.trace`) berichten ueber dieselben
Statements, traversieren sie aber statt sie nur zu digestieren: ein `TraceabilityGraph`, aus
genau der `StoreReader#readSnapshot`-Momentaufnahme gebaut (kein zweiter, bespoke SPARQL-Pfad),
folgt `arkreq:usesTerm`/`primaryActor`/`supportingActor`/`stepRealises` sowie dem
`mainStep`/`extensionStep`-Hop, um Requirement-, Term- und UseCase-Kanten zu berichten.

**Bewusste Abweichung von Entscheidung 2, nicht Bruch:** die beiden Ur-Tools kennen keinen
Praedikat-Namen (eine `SELECT ?s ?p ?o`); `TraceabilityGraph` kennt genau die sechs
`arkreq:`-Kanten oben und ist damit nicht mehr domaenenagnostisch im selben Sinn. Das ist derselbe
begrenzte Kompromiss wie `StoreResource#status()`/`#priority()` (issue #111, ADR-Konsequenzen
oben): eine vollstaendig generische "folge jedem objekt-typisierten Praedikat"-Traversierung
wuerde Rauschen berichten, das von den tatsaechlich interessanten Kanten nicht zu unterscheiden
waere. Composition-Root-Reinheit (Entscheidungen 1 und 3) bleibt unangetastet -- kein eigener BC,
derselbe geteilte `DatasetLifecycle`/`StoreReader`, keine RDF4J-Abhaengigkeit.

Die sechs traversierten `arkreq:`-Praedikat-IRIs sowie die vier Typ-IRIs, gegen die
`TraceabilityGraph` Ressourcen klassifiziert (`FunctionalRequirement`/`NonFunctionalRequirement`/
`Step`/`skos:Concept`), deklariert `TraceabilityGraph` **nicht** als eigene String-Literale,
sondern bezieht sie aus `ArkreqVocabulary` in `arknet-persistence-support` -- derselben einzigen
Quelle, aus der die `*-adapter-kogniordf`-Out-Adapter dieselben Kanten/Typen serialisieren (issue
#134). Das sind RDF-Serialisierungskonstanten (die IRI-Form von Ontologie-Praedikaten und
-Klassen), kein Domain-Vokabular: die BC-Cores sehen sie dank opaker Identitaet nie, sie wohnen
darum bei der uebrigen RDF-Serialisierungs-Technik (`SparqlTerms`) und nicht im dependency-freien
Kernel. Als reine `String`-Konstanten lassen sie `arknet-persistence-support` RDF4J-frei (ADR-007)
und `TraceabilityGraph` neutral. Vorher hielt jede der drei Stellen eine eigene Kopie -- ein
Praedikat- oder Typ-Rename kompilierte gruen weiter, liess die Traversierung die Kante bzw.
Klassifizierung aber still nicht mehr finden.

**Handle-Vertrag (Entscheidung 4) wiederverwendet, nicht dupliziert:** die
Resolutionslogik aus `StoreReportTools#resolveHandle` wurde nach `HandleResolver` extrahiert;
`impact_analysis`s Zielparameter und `resource_get`s `id`-Parameter teilen sich jetzt dieselbe
Implementierung statt zweier driftender Kopien.
