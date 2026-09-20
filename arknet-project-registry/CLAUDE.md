# arknet-project-registry

Kontext-Parent des Bounded Context "Projekt-Registry" (BC-5 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die eine Komponente `arknet-project` (bildet den Anker eines Aufrufs auf das registrierte Projekt ab).
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- die Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Kein Vokabularmodul (`<bc>-shared`): der Kontext hat keine besitzerlosen Identitaeten ausserhalb der einen Komponente, darum kein eigenes `dependencyManagement` (ADR-57).

Detail: `arknet-project/CLAUDE.md`.
