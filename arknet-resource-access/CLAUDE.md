# arknet-resource-access

Komponente fuer den generischen Zugriff auf die Ressourcen aller Bounded Contexts, selbst in keinem Bounded Context (ADR-69).
Maven-Parent `arknet-resource-access` (`packaging=pom`) direkt unter dem Root, ohne Kontext-Parent darueber; ihre drei Module `arknet-resource-access-core`, `arknet-resource-access-adapter-kogniordf`, `arknet-resource-access-adapter-mcp`.
Package `de.hauschel.arknet.resource`.
Kein `api`-Modul (ADR-58), kein Vokabularmodul, keine Ausbaustufe `starter`, `bom` oder `adapter-events` -- kein Ausloeser dafuer vorhanden.

Sie liest jeden Ressourcentyp zur Laufzeit aus Ontologie und Shapes und nennt in ihrem Code keine Klasse und keine Property aus dem Namespace eines Bounded Context, auch nicht aus dem geteilten Utility-Vokabular `arknet:` (`core#`).
Was sie selbst nennt, bedeutet in jedem Kontext dasselbe und steht im eigenen Namespace `arkres:` (`https://w3id.org/arknet/resource#`).
Die Composition Root verdrahtet sie wie jede andere Komponente und traegt selbst keine Anwendungslogik (ADR-70).

## Was fuer diese Komponente anders gilt

Ihr Kern arbeitet auf Tripeln (ADR-69, Folge 4).
Die Regeln fuer Kerne mit Domaenentypen -- Out-Ports ohne Tripel, Records ohne Zugriff auf den Graphen (ADR-8, ADR-45) -- gelten fuer sie nicht; ein Wechsel des Speichers traefe ihren Kern, nicht nur einen Adapter.
Der Kern darf die technologieneutralen kognio-rdf-Ports kennen, aber weder RDF4J noch Spring noch `arknet-mcp-support`.
Seine In-Ports tragen dennoch keine kognio-rdf-Typen, nur Strings, Records und Kernel-Typen: der treibende MCP-Adapter der Komponente sieht nur `..application.port.in..` und keine RDF-Technologie (`DependencyRulesTest` Regel 4).
Die Out-Ports (`ResourceRepository`, `ResourceBatch`) sprechen dagegen Tripel.
Kein Konstruktor eines Domaenentyps prueft die Invarianten einer ueber sie geschriebenen Ressource; was sich nicht als Shape oder als Regel in Daten ausdruecken laesst, hat hier keinen Platz.
Zur Build-Zeit haengt sie an keinem Modul eines Bounded Context; von den Klassen anderer Module darf nur die Composition Root sie nennen (Regel 17), auf Maven-Ebene zieht sie allein `arknet-architecture-tests` im Test-Scope.

## Module

- `arknet-resource-access-core`: frameworkfrei; haengt am Shared Kernel, an `arknet-persistence-support` (allein fuer `ChildFragments`, die stabilen Fragment-Namen der Kinder, ADR-64) und an den kognio-rdf-Ports `rdf-terms`/`rdf-dataset`.
  `domain.ArkresVocabulary` haelt die IRIs des `arkres:`-Vokabulars als String-Konstanten: die Aktivitaeten `Activity`, `Create`, `Update`, `AddTo`, `RemoveFrom`, `Delete`, ihre Properties `object`, `set`, `clear`, `field`, `value`, `position` und die Regeldaten `ResourceType`, `codePrefix`, `childKind`, `orderedBy`.
  Sie liegt im Core statt neben den `Ark*Vocabulary`-Klassen von `arknet-persistence-support`, weil jene die Namespaces der Kontexte spiegeln und diese die Sprache der Komponente selbst ist.
  Die Ontologie-Seite ist `arknet-ontology/src/main/resources/arknet-resource.ttl`.
  `domain.ResourceSchema` liest die Regeldaten aus den Shapes: Ressourcentypen mit Code-Praefix (an der Klasse), besessene Felder mit Kind-Art und Ordnungs-Property (an der Property-Shape, das Feld ist ihr `sh:path`).
  Ein besessenes Feld ohne `sh:class` fuer seine Kinder bricht den Aufbau des Schemas ab: erst die Klasse macht ein Kind zum Fokusknoten seiner Node-Shape, und die Engine typisiert jedes neue Kind mit ihr (ein anders getyptes Kind lehnt sie ab).
  `domain.SubmittedDocument` liest die Aktivitaeten eines geparsten Dokuments und lehnt ab, was ohne Store entscheidbar ist: jedes Tripel, das zu keiner Aktivitaet gehoert, einen Blank Node als Gegenstand zweier Creates, einen Zyklus aus Blank Nodes ausserhalb der neuen Ressourcen.
  `application.SubmitService` implementiert den In-Port `SubmitDocument`; `DocumentApplication` baut je beruehrter Ressource den Slice nach dem Dokument und nennt die Writes auf dem Batch.
- `arknet-resource-access-adapter-kogniordf`: Out-Adapter gegen den kognio-rdf-Store.
  `KognioRdfResourceRepositoryFactory` ist die einzige Klasse mit RDF4J: sie laedt die fuenf Shape-Dateien der Modellressourcen und die Modell-Ontologien per Dateiname vom Classpath (ohne `project-shapes.ttl`, das System-Dataset schreibt die Komponente nicht), baut daraus ein Gate und den Funnel und liefert Schema und Turtle-Parser fuer den Kern.
  `KognioRdfResourceRepositoryFactoryTest` gleicht die Dateiliste gegen das ab, was `arknet-ontology` ausliefert.
  `KognioRdfResourceRepository` setzt `ResourceRepository` auf `VersionedWriteFunnel#writeAll` um.
- `arknet-resource-access-adapter-mcp`: treibender Adapter mit den MCP-Werkzeugen; sieht vom Core nur `..application.port.in..` und keine RDF-Technologie.
  `McpAdapterModule` ist die einzige Klasse: sie gibt `ResourceAccessNamesNoModelNamespaceTest` eine Klasse im Modul, damit der Namespace-Scan es nachweislich erfasst, und entfaellt mit dem ersten Werkzeug.

## submit

Ein Dokument ist eine Tripelmenge ohne Reihenfolge und wird ganz oder gar nicht angewendet (ADR-59), in einem `writeAll`-Batch, mit je geaenderter Ressource einer Revision (ADR-66).
Die Engine baut zuerst alle Slices nach dem Dokument aus dem Stand davor, dann nennt sie die Writes; keine Aktivitaet sieht die Wirkung einer anderen.
Zwei Aktivitaeten auf demselben Feld einer Ressource, deren Ergebnis von ihrer Reihenfolge abhinge, lehnt sie ab; unabhaengig sind nur Sets verschiedener Sprachen und Listen-Aenderungen verschiedener Werte.
Eine neue Ressource steht im Dokument als Blank Node, die Engine vergibt IRI und, bei einem Typ mit Praefix, den naechsten freien Code ueber lebende und von geloeschten Ressourcen gehaltene Codes.
Ein besessenes Kind ist das Fragment `<Ressource>#<Art>-<Id>` und behaelt seinen Namen ueber jeden Write, der es behaelt; Positionen werden bei jedem Write auf das Feld lueckenlos ab 1 vergeben, die eines entfernten Kindes zaehlen im Stand vor dem Dokument.
Ein Update nennt keinen erwarteten Stand, wie die typgenauen Werkzeuge: ein verlorener Commit laesst die Engine das ganze Dokument gegen den neuen Stand noch einmal anwenden, hoechstens `SubmitService.MAX_ATTEMPTS`-mal.
Aktivitaeten werden nicht als eigene Ressourcen gespeichert; die Spur ist der History-Eintrag der Bibliothek je geaenderter Ressource.

Ein Gate prueft alle Ressourcentypen: die Shapes aller fuenf Dateien, die Modell-Ontologien als Axiome in den Daten, Subklassen-Reasoning an.
Die typgenauen Adapter setzen einen referenzierten Nachbarn nur mit seinem Typ in den Asserted Context und filtern fremde Shapes aus ihrem Gate; ein Gate ueber alles kann das nicht, der Typ allein aktivierte die Node-Shape des Nachbarn und scheiterte an dessen Pflichtfeldern.
Die Engine setzt darum die Slices aller ueber Verweise erreichbaren Ressourcen nach dem Dokument in den Asserted Context, transitiv; ein Verweis auf eine Ressource, die es danach nicht gibt, findet keinen Typ und verletzt das `sh:class` seiner Shape.
Diese Nachbarn liest sie ueber den Batch in der Schreibtransaktion; `SubmitRealStoreConcurrencyTest` haelt fest, dass ein paralleler Loescher des Gelesenen die Engine ihren Commit kostet und der zweite Lauf den Verweis ablehnt.

## Pruefung

Der Compiler sieht die Grenze eines Bounded Context fuer diese Komponente nicht; sie liegt in Namespace und Shapes und wird in `arknet-architecture-tests` geprueft (ADR-69, Folge 3).
`DependencyRulesTest` Regel 16 bis 18: keine Klasse nennt einen Typ eines Kontexts oder eines Vokabularmoduls (eine `Ark*Vocabulary`-Klasse faengt Regel 16 nur bei nicht-konstanter Nutzung, ihre String-Konstanten setzt javac ein); ausser ihr selbst nennt nur die Composition Root sie; ihr Core bleibt frei von RDF4J, Spring und `arknet-mcp-support`.
`ResourceAccessNamesNoModelNamespaceTest` durchsucht die Class-Files der drei Module nach jedem Namespace, den eine ausgelieferte Ontologie- oder Shape-Datei unter `https://w3id.org/arknet/` bindet, ausser `arkres:` -- ArchUnit sieht String-Konstanten nicht, auch keine eingesetzten `Ark*Vocabulary`-Konstanten; erst dieser Scan haelt sie fern.
`ResourceVocabularyMatchesOntologyTest` gleicht `ArkresVocabulary` beidseitig gegen `arknet-resource.ttl` ab.
