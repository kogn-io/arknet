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
Kein Konstruktor eines Domaenentyps prueft die Invarianten einer ueber sie geschriebenen Ressource; was sich nicht als Shape oder als Regel in Daten ausdruecken laesst, hat hier keinen Platz.
Zur Build-Zeit haengt sie an keinem Modul eines Bounded Context; von den Klassen anderer Module darf nur die Composition Root sie nennen (Regel 17), auf Maven-Ebene zieht sie allein `arknet-architecture-tests` im Test-Scope.

## Module

- `arknet-resource-access-core`: frameworkfrei, ohne Abhaengigkeit.
  `domain.ArkresVocabulary` haelt die IRIs des `arkres:`-Vokabulars als String-Konstanten: die Aktivitaeten `Activity`, `Create`, `Update`, `AddTo`, `RemoveFrom`, `Delete`.
  Sie liegt im Core statt neben den `Ark*Vocabulary`-Klassen von `arknet-persistence-support`, weil jene die Namespaces der Kontexte spiegeln und diese die Sprache der Komponente selbst ist.
  Die Ontologie-Seite ist `arknet-ontology/src/main/resources/arknet-resource.ttl`.
- `arknet-resource-access-adapter-kogniordf`: Out-Adapter gegen den kognio-rdf-Store; RDF4J nur in einer `*RepositoryFactory`.
  `KogniordfAdapterModule` ist die einzige Klasse: sie gibt `ResourceAccessNamesNoModelNamespaceTest` eine Klasse im Modul, damit der Namespace-Scan es nachweislich erfasst, und entfaellt mit der ersten Adapterklasse.
- `arknet-resource-access-adapter-mcp`: treibender Adapter mit den MCP-Werkzeugen; sieht vom Core nur `..application.port.in..` und keine RDF-Technologie.
  `McpAdapterModule` ist die einzige Klasse: sie gibt `ResourceAccessNamesNoModelNamespaceTest` eine Klasse im Modul, damit der Namespace-Scan es nachweislich erfasst, und entfaellt mit dem ersten Werkzeug.

## Pruefung

Der Compiler sieht die Grenze eines Bounded Context fuer diese Komponente nicht; sie liegt in Namespace und Shapes und wird in `arknet-architecture-tests` geprueft (ADR-69, Folge 3).
`DependencyRulesTest` Regel 16 bis 18: keine Klasse nennt einen Typ eines Kontexts oder eines Vokabularmoduls (eine `Ark*Vocabulary`-Klasse faengt Regel 16 nur bei nicht-konstanter Nutzung, ihre String-Konstanten setzt javac ein); ausser ihr selbst nennt nur die Composition Root sie; ihr Core bleibt frei von RDF4J, Spring und `arknet-mcp-support`.
`ResourceAccessNamesNoModelNamespaceTest` durchsucht die Class-Files der drei Module nach jedem Namespace, den eine ausgelieferte Ontologie- oder Shape-Datei unter `https://w3id.org/arknet/` bindet, ausser `arkres:` -- ArchUnit sieht String-Konstanten nicht, auch keine eingesetzten `Ark*Vocabulary`-Konstanten; erst dieser Scan haelt sie fern.
`ResourceVocabularyMatchesOntologyTest` gleicht `ArkresVocabulary` beidseitig gegen `arknet-resource.ttl` ab.
