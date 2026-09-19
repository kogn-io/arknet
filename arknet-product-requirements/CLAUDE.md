# arknet-product-requirements

Kontext-Parent des Bounded Context "Produkt & Anforderungen" (BC-1 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die beiden Komponenten `arknet-requirements` (Requirement- und Constraint-Lifecycle) und `arknet-use-cases` (Cockburn-Use-Cases) sowie deren gemeinsames Vokabularmodul `arknet-product-requirements-shared`.
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- jede Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Die beiden Komponenten sind nicht verschmolzen: sie teilen die Sprache des Kontexts, nicht ihre Module.
Sein `dependencyManagement` fuehrt nur das Vokabularmodul; die Versionen der Komponenten-Module fuehren die Komponenten-Parents darunter weiter selbst.

Kein Modul der einen Komponente haengt an einem Modul der anderen, und die ArchUnit-Regel `the_two_product_requirements_components_do_not_depend_on_each_other` in `arknet-architecture-tests` haelt das fest -- Maven kann es nicht, weil eine Abhaengigkeit zwischen zwei Modulen desselben Parents voellig legal waere.
Was eine Komponente von der anderen braucht, holt sie ueber einen eigenen Out-Port, den ihr eigener `-adapter-kogniordf` erfuellt, indem er den benannten Graphen der Nachbarin liest (Published Language, ADR-49): `arknet-use-cases-core` haelt dafuer `RequirementLookup` und `ConstraintLookup`, beide in beide Richtungen (Code -> Identitaet beim Schreiben, Identitaet -> Code beim Anzeigen).
Der Borrowed In-Port, mit dem `arknet-use-cases-adapter-mcp` frueher die Lese-In-Ports von `arknet-requirements-core` konsumierte, existiert innerhalb dieses Kontexts nicht mehr.
Ueber Kontextgrenzen hinweg gibt es ihn weiter: beide `-adapter-mcp` haengen fuer die Anzeige-Aufloesung `ResourceId -> TERM-N` am In-Port `ResolveTerms` von `arknet-ubiquitous-language-core`, `arknet-use-cases-adapter-mcp` zusaetzlich fuer `ROLE-N` an `ResolveRoles` von `arknet-actor-core`.

Kein `api`-Modul (ADR-58): die In-Port-Interfaces liegen in den Cores, eines je Anwendungsfall, und dass ein In-Adapter aus seinem Core nur `application.port.in` und Domaenentypen sieht, haelt die ArchUnit-Regel `driving_adapters_see_only_in_ports_and_domain_types`.
Keine Ausbaustufe `starter`, `bom` oder `adapter-events` -- kein Ausloeser dafuer vorhanden, es gibt genau eine Anwendung (`arknet-mcp`) und einen Reactor.

Detail je Baustein: `arknet-product-requirements-shared/CLAUDE.md`, `arknet-requirements/CLAUDE.md`, `arknet-use-cases/CLAUDE.md`.
