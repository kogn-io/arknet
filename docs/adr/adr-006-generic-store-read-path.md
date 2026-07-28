# ADR-006: Generischer Store-Lesepfad als Composition-Root-Werkzeug, nicht als Bounded Context

- Status: Accepted (2026-07-14)
- Verwandt: ADR-001 (austauschbarer Store), ADR-005 (Store-first), ADR-008 (In-Adapter als
  Tor zum BC -- die Lizenz, mit der der Report fremde Lese-In-Ports borgt), ADR-010 (Review-UI
  -- konsumiert diesen Lesepfad in-process), ADR-016 (Projekt-Identitaet -- dessen
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

## Nachtrag 2026-07-28: Der HTML-Report wird pro Bounded Context gelesen, der Digest bleibt generisch

Entscheidung 2 ("eine generische `SELECT ?s ?p ?o`") galt bis hierher fuer beide Ausgaben
gleichermassen: der Agent-Digest UND der HTML-Report entstanden aus derselben flachen
Statement-Liste. Fuer den Digest traegt das; fuer den Menschen nicht. Ein Use Case als Tripel
gelesen zerfaellt: sein Ablauf sind `n` eigene `arkreq:Step`-Subjekte unter opaken IRIs, deren
Reihenfolge in einem `arkreq:position`-Literal steckt, und seine Akteure und realisierten
Requirements sind weitere opake IRIs. Generisch gerendert ist das eine Tripel-Halde, kein
Anwendungsfall -- und der Report ist genau das Artefakt, das ein Fachbereich lesen soll.

**Der HTML-Report wird pro Bounded Context aus deren Lese-In-Ports zusammengesetzt**
(`ListBoundedContexts`/`ListRequirements`/`ListUseCases`/`ListTerms`, plus
`ResolveRequirements` fuer die Anzeige-Codes referenzierter Requirement-Identitaeten).
Der Kontext, der ein Modellelement geschrieben hat, weiss es zurueckzulesen; der Report fragt
ihn, statt die Antwort im Composition Root neu herzuleiten. Dass ein treibender Adapter dafuer
fremde In-Ports borgt, ist keine neue Freiheit, sondern die aus ADR-008 -- hier fuer eine
Anzeige statt fuer eine Tool-Antwort. Die BC-Cores bleiben unberuehrt.

Unberuehrt bleiben ebenso die Entscheidungen 1, 3 und 4: kein eigener BC, derselbe geteilte
`DatasetLifecycle`/`StoreReader`, derselbe Handle-Vertrag, keine RDF4J-Abhaengigkeit im
Composition Root.

Glossar-Referenzen laufen dabei ueber `ListTerms` statt ueber `ResolveTerms`: der Report
beantwortet nicht nur "wie heisst dieser Begriff", sondern auch "nennt ein Text einen Begriff,
auf den keine Kante zeigt" -- und dafuer genuegt die schmale Aufloesung bereits verlinkter
Identitaeten nicht.

**Der Report glaettet die Luecke zwischen Prosa und Modell nicht, sondern zeigt sie.** Eine
Term-Erwaehnung mit Kante wird zum Link, eine ohne Kante bleibt als solche sichtbar. Das folgt
aus der Rolle als Kontrollausgabe: beides gleich zu rendern hiesse, eine Beziehung zu behaupten,
die der Store nicht haelt. Mechanik und Abgrenzungen: `arknet-mcp/CLAUDE.md`.

**Der Datenpfad des Agenten bleibt generisch.** Der Rueckgabewert von `store_overview` ist
weiterhin der domaenenagnostische Text-Digest aus der einen `SELECT ?s ?p ?o`. Zwei Zielgruppen,
zwei Formen -- das war schon die tragende Unterscheidung dieses ADR, hier nur konsequent
zuende gefuehrt.

**Der Snapshot bleibt das Auffangnetz des Reports.** Jede Karte haelt ihre Roh-Tripel eine
Ebene tiefer bereit, und alles, was kein Kontext als Modellelement beansprucht, erscheint
unveraendert generisch in einem eigenen Abschnitt. Der Report kann damit nichts verbergen,
was im Store steht -- die Eigenschaft, die ihn ueberhaupt als Kontrollausgabe brauchbar macht.
Genau eine strukturelle Ausnahme: `arkreq:Step`-Ressourcen, die von einem Use Case aus
erreichbar sind, werden dort unterdrueckt, weil sie im Ablauf jener Karte bereits stehen; ein
Step, den kein Use Case referenziert, wird nicht unterdrueckt -- ein Waise ist gerade das,
was dieser Abschnitt zeigen soll.

### Konsequenzen dieses Nachtrags

**Positiv:**

- Der Report liest sich als Modell: Use Case mit nummeriertem Ablauf, Requirement mit
  Akzeptanzkriterien, Begriff mit Definition, Bounded Context mit Domain Vision.
- Keine zweite Interpretation derselben Tripel. Reihenfolge, Aufloesung opaker Referenzen und
  Feldsemantik gibt es genau einmal, im besitzenden Kontext -- ein Praedikat-Rename kann den
  Report nicht mehr still falsch rendern lassen, waehrend die Tools korrekt bleiben.
- Der Report ist damit derselbe Konsument, den ADR-010 fuer die Review-UI vorsieht (Lesen ueber
  die `*_list`/`*_get`-In-Ports plus den generischen Lesepfad). Was hier entsteht, ist die
  Vorlage fuer jene UI, nicht ein zweiter, konkurrierender Weg.

**Negativ / bewusst:**

- Die urspruengliche Konsequenz "neue BCs erscheinen ohne Report-Aenderung" gilt so nicht mehr:
  ein neuer Bounded Context erscheint weiterhin vollstaendig, aber roh, bis er einen eigenen
  Abschnitt bekommt. Der Preis ist bewusst -- eine Darstellung, die ein Modellelement als das
  zeigt, was es ist, kann nicht typunabhaengig sein.
- Der Report haengt jetzt an vier Lese-In-Ports. Weil `store_overview` das Werkzeug ist, zu dem
  ein Nutzer greift, wenn er den Store fuer kaputt haelt, darf keiner davon den Aufruf fallen
  lassen: ein Abschnitt, dessen In-Port wirft, wird als sichtbare Warnung im Report gemeldet und
  seine Ressourcen fallen in den generischen Abschnitt zurueck. Ein still fehlender Abschnitt
  laese sich sonst als "Store ist leer".

### Alternativen

- **Die Sicht aus dem Snapshot rekonstruieren** (typ-spezifische Renderer, die
  `arkreq:mainStep`/`position`/`primaryActor` selbst auswerten). Verworfen: das dupliziert die
  Lese- und Aufloesungslogik der Out-Adapter im Composition Root, mit dem klassischen
  Drift-Risiko zweier Kopien -- und die Menge hartkodierter Praedikate waere um eine
  Groessenordnung gewachsen statt begrenzt zu bleiben.
- **Alles generisch lassen und nur besser stylen.** Verworfen: kein Styling macht aus `n`
  opaken Step-Subjekten einen lesbaren Ablauf. Das Problem ist die Struktur, nicht die Optik.
