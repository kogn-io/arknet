# arknet-actor-ontology

Ontologiemodul des Bounded Context "Akteur" (BC-4 im Store): die Published Language des Kontexts als Turtle, nur `.ttl`-Ressourcen, kein Java.
Live sind `arknet-actor.ttl` (Ontologie: Actor-Taxonomie und Role) und `actor-shapes.ttl` (Shapes von Actor und Role).
`parked/` haelt `arknet-process.ttl`, `process-shapes.ttl` und `sparql/process-queries.sparql` (Prozess-Vokabular im selben Namespace, ohne Konsument): ohne lebenden Konsumenten, nicht veroeffentlicht, nicht im Index.
`META-INF/arknet/ontology.index` nennt die live Dateien mit absolutem Classpath-Namen, eine je Zeile; `OntologyIndexListsEveryShippedFileTest` haelt ihn gegen den Modulinhalt fest.
Die Classpath-Namen sind Vertrag und haengen nicht daran, welches Modul eine Datei liefert.
Im Compile-Scope haengen allein `arknet-actor-register-adapter-kogniordf` daran, die ihre Dateien per Classpath-Namen in ihr SHACL-Gate laden.
`arknet-resource-access-adapter-kogniordf` findet die Dateien zur Laufzeit ueber den Index und zieht das Modul nur im Test-Scope (ADR-69, Folge 8).
Kein Vokabularmodul im Sinn von ADR-57: das Modul traegt keinen Java-Typ.
Seine Shapes nennen als `sh:targetClass` nur Klassen aus `arkproc:`, und keine Zielklasse formt ein zweites Modul (`ContextShapesTargetOwnNamespaceTest`).
`actor-shapes.ttl` traegt neben den Shapes die Regeldaten fuer `submit` (`arkres:`): die vier konkreten Actor-Typen (Code `ACTOR-`) und `arkproc:Role` (`ROLE-`) sind Ressourcentypen, `arkproc:Actor` allein nicht; kein Statusfeld, der Actor-Typ bleibt als `rdf:type` unveraenderlich (Engine-Garantie).
Welche Regel des Kerns wo steht, fuehrt `docs/rules-inventory.md`.
Benennung, Aenderung einer Namespace-IRI und Shapes ohne Schreibpfad regelt `arknet-ontology/CLAUDE.md` fuer alle Ontologiemodule.
