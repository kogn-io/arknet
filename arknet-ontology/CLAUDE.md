# arknet-ontology

Nur .ttl-Ressourcen (Ontologie-Module, Shapes).

`src/main/resources/` traegt nur aktive Module -- die, deren Klassen tatsaechlich von einem BC
referenziert werden (Axiome/Shapes geladen ueber `AXIOMS_RESOURCE`/`SHAPES_RESOURCE` in den
`KognioRdf*RepositoryFactory`-Klassen, oder wie bei `arknet-provenance.ttl` als Vokabular-Quelle
gegen `Arkprov/ArkprjVocabulary` per Architecture-Test abgeglichen). `parked/` haelt Module ohne
lebenden Konsumenten (kein BC, keine Java-Referenz) -- Ontologie existiert, wird aber (noch) nicht
gebraucht und ist darum auch nicht unter `w3id.org/arknet/` veroeffentlicht. Verschieben zurueck
nach `src/main/resources/` erst, wenn ein BC das Modul tatsaechlich konsumiert.

Ein Namespace-Name behauptet nicht mehr, als der Namespace traegt.
Ein Namespace-Name ist publizierte Sprache, er steht in jedem Tripel: ein Name, der einen ganzen Gegenstandsbereich ankuendigt, aber live nur einen einzelnen Begriff traegt, fuehrt jeden in die Irre, der aus der IRI auf den Inhalt schliesst.
Traegt ein Namespace live weniger, als sein Name verspricht, wird der Name auf das eingeengt, was live ist.
Ob ein Name mehr behauptet, als der Namespace traegt, ist eine Beurteilung und wird im Review entschieden, keine mechanische Pruefung.
Das ist eine andere Regel als die Namespace-Spannweite (ADR-12 im arknet-eigenen Store: kein Namespace spannt ueber zwei Kontexte) -- jene ist mechanisch pruefbar und eine Entscheidung ueber die Gestalt des Systems, diese hier eine Beurteilungsfrage ueber Benennung.
