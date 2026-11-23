# arknet-ontology

Nur .ttl-Ressourcen, kein Java: die Ontologien und Shapes, die keinem der vier Modellkontexte gehoeren.
Live sind `arknet-core.ttl` (generisches Utility-Vokabular `arknet:`, auf dem die Kontext-Ontologien aufbauen), `arknet-resource.ttl` (`arkres:`, Vokabular der Komponente `arknet-resource-access`), `arknet-provenance.ttl` (`arkprov:`, von keinem Schreibpfad genutzt) sowie `arknet-project.ttl` und `project-shapes.ttl` der Projekt-Registry (BC-5).
`parked/` haelt `arknet-privacy.ttl` und `privacy-shapes.ttl` (`arkpriv:`).

Die Ontologie und die Shapes von Produkt & Anforderungen, Domain Modelling, Architektur & Entscheidungen und Akteur liefert je ein Ontologiemodul unter dem Kontext-Parent: `arknet-product-requirements-ontology`, `arknet-domain-modelling-ontology`, `arknet-architecture-decisions-ontology`, `arknet-actor-ontology`.
Jedes Ontologiemodul, dieses eingeschlossen, nennt seine live Dateien in `META-INF/arknet/ontology.index`, eine je Zeile mit absolutem Classpath-Namen; `parked/` steht nie im Index.
`arknet-resource-access` findet die Dateien der Kontexte allein ueber diese Indizes, `arknet-architecture-tests` leitet daraus jeden Ontologie-Abgleich ab, `OntologyIndexListsEveryShippedFileTest` haelt jeden Index gegen den Inhalt seines Moduls fest.
Die Classpath-Namen sind ueber alle Ontologiemodule eindeutig und Vertrag: die typgenauen Out-Adapter laden ihre Dateien per Namen.
Die Regeln dieser Datei gelten fuer alle Ontologiemodule.

`src/main/resources/` eines Ontologiemoduls traegt nur aktive Module -- die, deren Klassen tatsaechlich von einem BC
oder einer Komponente referenziert werden (Axiome/Shapes geladen ueber `AXIOMS_RESOURCE`/`SHAPES_RESOURCE` in den
`KognioRdf*RepositoryFactory`-Klassen, oder wie bei `arknet-project.ttl` und `arknet-resource.ttl` als Vokabular-Quelle
gegen `ArkprjVocabulary` bzw. `ArkresVocabulary` per Architecture-Test abgeglichen). `parked/` haelt Module ohne
lebenden Konsumenten (kein BC, keine Komponente, keine Java-Referenz) -- Ontologie existiert, wird aber (noch) nicht
gebraucht und ist darum auch nicht unter `w3id.org/arknet/` veroeffentlicht. Verschieben zurueck
nach `src/main/resources/` erst, wenn ein BC oder eine Komponente das Modul tatsaechlich konsumiert.

Die Shape-Dateien der Modellressourcen in den vier Kontext-Ontologiemodulen tragen neben den Shapes Regeldaten des generischen Ressourcenzugriffs im Namespace `arkres:` (`arknet-resource.ttl`): welche Klassen Ressourcentypen sind und mit welchem Praefix ihr fachlicher Code beginnt (an der Klasse), welche Felder ihre Kinder besitzen und nach welcher Property diese geordnet sind (an der Property-Shape).
SHACL wertet diese Tripel nicht aus; die typgenauen Kerne ignorieren sie, `arknet-resource-access` liest allein sie und nennt keinen Ressourcentyp im Code.
`project-shapes.ttl` traegt keine, weil die Projekt-Registry im System-Dataset liegt, das der generische Zugriff nicht schreibt.

Ein Namespace-Name behauptet nicht mehr, als der Namespace traegt.
Ein Namespace-Name ist publizierte Sprache, er steht in jedem Tripel: ein Name, der einen ganzen Gegenstandsbereich ankuendigt, aber live nur einen einzelnen Begriff traegt, fuehrt jeden in die Irre, der aus der IRI auf den Inhalt schliesst.
Traegt ein Namespace live weniger, als sein Name verspricht, wird der Name auf das eingeengt, was live ist -- wann, regelt der Absatz zur Aenderung einer Namespace-IRI unten.
Ob ein Name mehr behauptet, als der Namespace traegt, ist eine Beurteilung und wird im Review entschieden, keine mechanische Pruefung.
Das ist eine andere Regel als die Namespace-Zuordnung (Architekturentscheidung im arknet-eigenen Store, ADR-67 -- sinngemaess: jeder schreibende Bounded Context hat genau einen eigenen Namespace; der genaue Zuschnitt gilt so, wie er im Store steht) -- jene ist mechanisch pruefbar und eine Entscheidung ueber die Gestalt des Systems, diese hier eine Beurteilungsfrage ueber Benennung.

Wann eine Namespace-IRI geaendert wird.
Eine Namespace-IRI steht in jedem Tripel jedes Datasets; jede Aenderung ist eine Datenmigration.
Eine Namespace-IRI wird deshalb nur geaendert, wenn der Namespace die Spannweiten-Regel oben verletzt, nie aus Symmetrie- oder Namensgruenden -- es sei denn, eine Migration schreibt aus anderem Grund ohnehin jedes Tripel jedes Datasets neu (etwa ein geaendertes Graph-Layout); in diesem Schnitt kostet eine Umbenennung keine eigene Migration.
Auch eine Einengung nach der Benennungsregel oben wartet auf eine solche Migration.

Die Ontologie darf der Implementierung vorlaufen, die Shapes duerfen es nicht.
Ein deklarierter Begriff ohne Schreibpfad ist Vokabular-Vorrat und bleibt stehen; eine Shape ohne Schreibpfad behauptet eine Pruefung, die nie laeuft, und wird geloescht.
Wer den Schreibpfad spaeter baut, schreibt die Shape mit ihm zusammen -- dann gegen echte Anforderungen statt gegen eine Vermutung von heute.
Damit ein Vorrat spaeter nicht als Altlast gestrichen wird, traegt der deklarierte Begriff seinen Grund als `skos:note` (so bei `arkreq:Milestone` und `arkreq:Release`).
