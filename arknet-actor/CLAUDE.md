# arknet-actor

Kontext-Parent des Bounded Context "Akteur" (BC-4 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die eine Komponente `arknet-actor-register` (Actor-Lifecycle plus Role als zweiter Ressourcentyp) und das Ontologiemodul `arknet-actor-ontology` (Ontologie `arkproc:` und Shapes des Kontexts).
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- die Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Kein Vokabularmodul (`<bc>-shared`): der Kontext hat keine besitzerlosen Identitaeten ausserhalb der einen Komponente (ADR-57).
Sein `dependencyManagement` fuehrt allein das Ontologiemodul.

Detail: `arknet-actor-register/CLAUDE.md`.
