# arknet-project-registry-ontology

Ontologiemodul des Bounded Context "Projekt-Registry" (BC-5 im Store): die Published Language des Kontexts als Turtle, nur `.ttl`-Ressourcen, kein Java.
Live sind `arknet-project.ttl` (Ontologie: Project, Anchor, AnchorType, `defaultLanguage`, `maintainedLanguage`) und `project-shapes.ttl` (Shapes des Projekts).
Es gibt keine geparkten Dateien.
`META-INF/arknet/ontology.index` nennt die live Dateien mit absolutem Classpath-Namen, eine je Zeile; `OntologyIndexListsEveryShippedFileTest` haelt ihn gegen den Modulinhalt fest.
Die Classpath-Namen sind Vertrag und haengen nicht daran, welches Modul eine Datei liefert.
Im Compile-Scope haengt allein `arknet-project-adapter-kogniordf` daran, der `project-shapes.ttl` per Classpath-Namen in sein SHACL-Gate laedt.
`arknet-resource-access-adapter-kogniordf` zieht das Modul nur im Test-Scope: die Projekt-Registry liegt im System-Dataset, das der generische Zugriff nicht schreibt, und `OUTSIDE_GENERIC_ACCESS` schliesst beide Dateien per Namen aus; ein Test haelt diese Namen gegen die Indizes.
`project-shapes.ttl` traegt keine Regeldaten des generischen Zugriffs (`arkres:`).
Kein Vokabularmodul im Sinn von ADR-57: das Modul traegt keinen Java-Typ; die IRIs als Java-Konstanten stehen in `ArkprjVocabulary` (`arknet-persistence-support`), ein Architekturtest gleicht sie gegen `arknet-project.ttl` ab.
Seine Shapes nennen als `sh:targetClass` nur Klassen aus `arkprj:` (`ContextShapesTargetOwnNamespaceTest`).
Benennung, Aenderung einer Namespace-IRI und Shapes ohne Schreibpfad regelt `arknet-ontology/CLAUDE.md` fuer alle Ontologiemodule.
