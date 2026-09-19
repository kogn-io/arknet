# arknet-domain-modelling

Maven-Parent (packaging pom) des Bounded Context **Domain Modelling**; aggregiert, traegt keinen Code und keine Ports.
Der Kontext haelt zwei Komponenten -- `arknet-ubiquitous-language` (Glossar, `term_*`) und `arknet-bounded-context` (Kontextlandkarte, `bc_*`) -- sowie das Vokabularmodul `arknet-domain-modelling-shared`.
Der Kontext ist die Grenze der Bedeutung, nicht die Bauform: jede Komponente bleibt ein eigenes Hexagon mit eigenem Aggregat, eigenen Ports und eigenen Adaptern (ADR-48).
`dependencyManagement` hier verwaltet allein die Version des Vokabularmoduls; die Komponenten-Parents verwalten ihre eigenen Module weiterhin selbst.

## Was der gemeinsame Kontext erlaubt -- und was nicht

Ein gemeinsamer Parent ist Aggregation, keine Erlaubnis: kein Modul der einen Komponente haengt im compile scope an einem Modul der anderen.
Eine Ausnahme im test scope ist gewollt: `arknet-bounded-context-adapter-kogniordf` zieht den Out-Adapter der Nachbarin, damit ein Schema-Test einen Term durch deren echtes Repository schreibt und durch `KognioRdfTermLookup` wieder aufloest -- das Regressionssignal gegen Schema-Drift, das ADR-49 als Preis der Published-Language-Kopplung nennt.
Was eine Komponente von der Nachbarin braucht, liest sie ueber einen eigenen Out-Port aus deren Published Language im geteilten Store -- derselbe Mechanismus wie ueber eine Kontextgrenze (ADR-49).
Konkret ist das `TermLookup` im `arknet-bounded-context-core`, in beide Richtungen: `resolveByCode` fuer den Schreibpfad von `bc_link_term`, `codesById` fuer die Anzeige-Rueckrichtung in `bc_get`/`bc_list`.
Geborgte In-Ports gibt es in diesem Kontext nicht mehr; `DependencyRulesTest` (Regel 9) haelt die Trennung in beide Richtungen fest.
Ein `api`-Modul je Komponente gibt es nicht (ADR-58); die In-Port-Schnittstellen liegen im jeweiligen Core unter `application.port.in`, und dass ein In-Adapter nichts anderes aus seinem Core sieht, haelt Regel 8 desselben Tests.
