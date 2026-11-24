# arknet-architecture-decisions-ontology

Ontologiemodul des Bounded Context "Architektur & Entscheidungen" (BC-3 im Store): die Published Language des Kontexts als Turtle, nur `.ttl`-Ressourcen, kein Java.
Live sind `arknet-architecture.ttl` (Ontologie) und `architecture-shapes.ttl` (Shapes des Architecture Decision Record, seiner Konsequenzen und erwogenen Optionen).
`parked/` haelt `arknet-architecture_parked.ttl` (uebrige ISO-42010-Architekturbeschreibung) und `architecture-shapes_parked.ttl`: ohne lebenden Konsumenten, nicht veroeffentlicht, nicht im Index.
`META-INF/arknet/ontology.index` nennt die live Dateien mit absolutem Classpath-Namen, eine je Zeile; `OntologyIndexListsEveryShippedFileTest` haelt ihn gegen den Modulinhalt fest.
Die Classpath-Namen sind Vertrag und haengen nicht daran, welches Modul eine Datei liefert.
Im Compile-Scope haengen allein `arknet-adr-adapter-kogniordf` daran, die ihre Dateien per Classpath-Namen in ihr SHACL-Gate laden.
`arknet-resource-access-adapter-kogniordf` findet die Dateien zur Laufzeit ueber den Index und zieht das Modul nur im Test-Scope (ADR-69, Folge 8).
Kein Vokabularmodul im Sinn von ADR-57: das Modul traegt keinen Java-Typ.
`architecture-shapes.ttl` traegt neben den Shapes die Regeldaten fuer `submit` (`arkres:`): Ressourcentyp und Kind-Arten, dazu den Lebenszyklus des Records am Statusfeld -- Anfangsstatus, Uebergaenge mit ihren Bedingungen, loeschbare Status, die ab der Entscheidung eingefrorene Feldgruppe und das Entscheidungsdatum als Standardwert am Uebergang.
Die Bedingungen sind Shapes ohne Ziel: sie gelten nur beim Uebergang, das Gate prueft sie nie als Endzustand.
Seine Shapes nennen als `sh:targetClass` nur Klassen aus `arkarch:`, und keine Zielklasse formt ein zweites Modul (`ContextShapesTargetOwnNamespaceTest`).
Benennung, Aenderung einer Namespace-IRI und Shapes ohne Schreibpfad regelt `arknet-ontology/CLAUDE.md` fuer alle Ontologiemodule.
