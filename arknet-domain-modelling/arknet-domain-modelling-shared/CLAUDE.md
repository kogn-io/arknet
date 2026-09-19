# arknet-domain-modelling-shared

Vokabularmodul des Bounded Context Domain Modelling (ADR-57): die typisierten Business-Codes, die beide Komponenten des Kontexts sprechen.
Package `de.hauschel.arknet.dm.shared`, Inhalt heute genau ein Typ -- `TermCode`, das `TERM-N`-Label eines Glossarbegriffs (`dcterms:identifier`).
`arknet-ubiquitous-language-core` besitzt den Begriff und vergibt den Code; `arknet-bounded-context-core` spricht ihn am Rand seiner Ports, weil ein Nutzer `TERM-1` tippt und `TERM-1` wiedersehen will.

## Aufnahmeregel

Hinein kommt nur, was kein Aggregat besitzt und was mehr als eine Komponente dieses Kontexts braucht: Werte und ihre Regeln, kein Verhalten.
`TermId` gehoert deshalb nicht hierher (die opake Identitaet des Term-Aggregats, Besitz der Glossar-Komponente), `ProjectId`/`ResourceId` ebenfalls nicht (Shared Kernel aller Kontexte, ADR-56).
Einen Verweistyp auf einen Glossarbegriff traegt dieses Modul nicht: innerhalb dieses Kontexts ist der Begriff selbst zuhause, die Kontextlandkarte verweist auf ihn ueber seine Identitaet und benennt ihn ueber `TermCode`.
Das Modul haengt an keinem anderen Modul -- das ist der Grund, warum zwei Cores es teilen duerfen, und `DependencyRulesTest` (Regel 7) haelt es fest.
