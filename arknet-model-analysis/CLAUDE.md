# arknet-model-analysis

Kontext-Parent des Bounded Context "Model Analysis" (BC-6 im Store): `packaging=pom`, nur Aggregation, kein Code.
Er haelt die eine Komponente `arknet-model-evaluation` (die rein lesende, kontextuebergreifende Auswertung: Wirkungsanalyse, Verfolgungsmatrix, verwaiste Ressourcen, Rollen-Use-Case-Matrix, Begriffs-Kookkurrenz, Bestandspruefungen).
Ein Bounded Context ist ein Maven-Parent, kein Hexagon -- die Komponente bleibt ihr eigenes Hexagon mit eigenem Core, eigenen Ports und eigenen Adaptern.
Kein Vokabularmodul (`<bc>-shared`): der Kontext hat keine besitzerlosen Identitaeten ausserhalb der einen Komponente, darum kein eigenes `dependencyManagement` (ADR-57).

Detail: `arknet-model-evaluation/CLAUDE.md`.
