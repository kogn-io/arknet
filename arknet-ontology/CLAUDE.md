# arknet-ontology

Nur .ttl-Ressourcen (Ontologie-Module, Shapes).

`src/main/resources/` traegt nur aktive Module -- die, deren Klassen tatsaechlich von einem BC
referenziert werden (Axiome/Shapes geladen ueber `AXIOMS_RESOURCE`/`SHAPES_RESOURCE` in den
`KognioRdf*RepositoryFactory`-Klassen, oder wie bei `arknet-provenance.ttl` als Vokabular-Quelle
gegen `Arkprov/ArkprjVocabulary` per Architecture-Test abgeglichen). `parked/` haelt Module ohne
lebenden Konsumenten (kein BC, keine Java-Referenz) -- Ontologie existiert, wird aber (noch) nicht
gebraucht und ist darum auch nicht unter `w3id.org/arknet/` veroeffentlicht. Verschieben zurueck
nach `src/main/resources/` erst, wenn ein BC das Modul tatsaechlich konsumiert.
