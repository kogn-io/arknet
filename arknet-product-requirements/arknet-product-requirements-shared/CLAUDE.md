# arknet-product-requirements-shared

Vokabularmodul des Bounded Context "Produkt & Anforderungen" (ADR-57): die Sprache des Kontexts als Code, geteilt von seinen beiden Komponenten.
Package `de.hauschel.arknet.pr.shared`.
Drei Typen, alle `record`, alle ohne Verhalten ueber den Wert hinaus: `RequirementCode` (`FR-1`, `NFR-7`), `ConstraintCode` (`TCON-1`, `BCON-3`, `RCON-1`) und `TermRef` (der Verweis auf einen Glossarbegriff, getragen als opake `ResourceId`).

Aufnahmeregel: jeder Typ hier beantwortet die Frage "welches Aggregat besitzt dich?" mit "keines", und mehr als eine Komponente des Kontexts braucht ihn.
`RequirementCode` und `ConstraintCode` erfuellen das, seit `arknet-use-cases` die Codes der Nachbarkomponente selbst traegt, statt sie ueber deren In-Port anzeigen zu lassen (ADR-49).
`TermRef` erfuellt es, weil beide Komponenten auf das Glossar verweisen und dasselbe damit meinen; er gehoert bewusst NICHT in den Shared Kernel aller Kontexte, denn der Verweis auf einen Glossarbegriff ist Sprache des verweisenden Kontexts -- jeder Kontext, der aufs Glossar zeigt, haelt seinen eigenen.

Nicht hier: `RequirementId`/`ConstraintId`/`UseCaseId` und die Aggregate selbst (die haben einen Besitzer und liegen in dessen Core), `ConstraintRef` von `arknet-requirements-core` (zeigt auf ein Aggregat desselben Cores), `RequirementRef`/`ConstraintRef`/`RoleRef` von `arknet-use-cases-core` (Verweise, die der Use-Case als Teil seines Zustands haelt).

Das Modul haengt an genau einem anderen: `arknet-shared-kernel`, dessen `ResourceId` der `TermRef` umschliesst.
`DependencyRulesTest` (Regel 7) haelt das fest und ist ueber das Paketmuster `de.hauschel.arknet.*.shared..` formuliert, damit das Vokabularmodul eines zweiten Kontexts ihr unterliegt, sobald es existiert.
