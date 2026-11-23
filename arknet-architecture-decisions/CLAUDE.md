# arknet-architecture-decisions

Kontext-Parent des Bounded Context "Architektur & Entscheidungen" (BC-3 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die eine Komponente `arknet-adr` (Architecture-Decision-Record-Lifecycle) und das Ontologiemodul `arknet-architecture-decisions-ontology` (Ontologie `arkarch:` und Shapes des Kontexts).
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- die Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Kein Vokabularmodul (`<bc>-shared`): der Kontext hat keine besitzerlosen Identitaeten ausserhalb der einen Komponente (ADR-57).
Sein `dependencyManagement` fuehrt allein das Ontologiemodul.

Detail: `arknet-adr/CLAUDE.md`.
