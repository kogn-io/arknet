# arknet-architecture-decisions

Kontext-Parent des Bounded Context "Architektur & Entscheidungen" (BC-3 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die eine Komponente `arknet-adr` (Architecture-Decision-Record-Lifecycle).
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- die Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Kein Vokabularmodul (`<bc>-shared`): der Kontext hat keine besitzerlosen Identitaeten ausserhalb der einen Komponente, darum kein eigenes `dependencyManagement` (ADR-57).

Detail: `arknet-adr/CLAUDE.md`.
