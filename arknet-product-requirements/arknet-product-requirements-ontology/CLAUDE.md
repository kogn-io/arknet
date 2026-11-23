# arknet-product-requirements-ontology

Ontologiemodul des Bounded Context "Produkt & Anforderungen" (BC-1 im Store): die Published Language des Kontexts als Turtle, nur `.ttl`-Ressourcen, kein Java.
Live sind `arknet-requirements.ttl` (Ontologie) und `requirements-shapes.ttl` (Shapes von Requirement, Constraint, Use Case und ihren Kindern).
`META-INF/arknet/ontology.index` nennt die live Dateien mit absolutem Classpath-Namen, eine je Zeile; `OntologyIndexListsEveryShippedFileTest` haelt ihn gegen den Modulinhalt fest.
Die Classpath-Namen sind Vertrag und haengen nicht daran, welches Modul eine Datei liefert.
Im Compile-Scope haengen allein `arknet-requirements-adapter-kogniordf` und `arknet-use-cases-adapter-kogniordf` daran, die ihre Dateien per Classpath-Namen in ihr SHACL-Gate laden.
`arknet-resource-access-adapter-kogniordf` findet die Dateien zur Laufzeit ueber den Index und zieht das Modul nur im Test-Scope (ADR-69, Folge 8).
Kein Vokabularmodul im Sinn von ADR-57: das Modul traegt keinen Java-Typ.
Seine Shapes nennen als `sh:targetClass` nur Klassen aus `arkreq:`, und keine Zielklasse formt ein zweites Modul (`ContextShapesTargetOwnNamespaceTest`).
Benennung, Aenderung einer Namespace-IRI und Shapes ohne Schreibpfad regelt `arknet-ontology/CLAUDE.md` fuer alle Ontologiemodule.
