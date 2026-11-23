# arknet-domain-modelling-ontology

Ontologiemodul des Bounded Context "Domain Modelling" (BC-2 im Store): die Published Language des Kontexts als Turtle, nur `.ttl`-Ressourcen, kein Java.
Live sind `arknet-ddd.ttl` (Ontologie), `ul-shapes.ttl` (Shapes des Glossarbegriffs als `skos:Concept` und eine zweite Node-Shape auf `arkddd:BoundedContext`) und `arknet-shapes.ttl` (Shapes von Bounded Context und Context Relationship).
`parked/` haelt `arknet-ddd_parked.ttl` (ContextMap, taktisches DDD) und `arknet-ddd-shapes.ttl`: ohne lebenden Konsumenten, nicht veroeffentlicht, nicht im Index.
`META-INF/arknet/ontology.index` nennt die live Dateien mit absolutem Classpath-Namen, eine je Zeile; `OntologyIndexListsEveryShippedFileTest` haelt ihn gegen den Modulinhalt fest.
Die Classpath-Namen sind Vertrag und haengen nicht daran, welches Modul eine Datei liefert.
Im Compile-Scope haengen allein `arknet-ubiquitous-language-adapter-kogniordf` und `arknet-bounded-context-adapter-kogniordf` daran, die ihre Dateien per Classpath-Namen in ihr SHACL-Gate laden.
`arknet-resource-access-adapter-kogniordf` findet die Dateien zur Laufzeit ueber den Index und zieht das Modul nur im Test-Scope (ADR-69, Folge 8).
Kein Vokabularmodul im Sinn von ADR-57: das Modul traegt keinen Java-Typ.
Seine Shapes nennen als `sh:targetClass` nur Klassen aus `arkddd:` und `skos:Concept`, und keine Zielklasse formt ein zweites Modul (`ContextShapesTargetOwnNamespaceTest`).
`skos:Concept` ist die Klasse eines fremden Standards, als die der Glossarbegriff gespeichert wird; formen darf sie nur dieses Modul.
Regeln, die SHACL Core nicht ausdrueckt (Begriff nicht sein eigener `skos:broader`/`skos:related`, kein `skos:broader`-Zyklus, ein `skos:prefLabel`-Wortlaut unter allen Tags), stehen als `sh:sparql`-Constraints an `ulshapes:TermShape`.
Benennung, Aenderung einer Namespace-IRI und Shapes ohne Schreibpfad regelt `arknet-ontology/CLAUDE.md` fuer alle Ontologiemodule.
