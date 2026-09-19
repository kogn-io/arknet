# arknet-mcp-support

Technischer Support der treibenden MCP-Adapter und des Composition Root -- Package `de.hauschel.arknet.mcpsupport`, sieben Typen, reines JDK plus `arknet-shared-kernel`.
Was hier liegt, nennt kein `*-core` und kein Vokabularmodul; die Aufrufer sind ausschliesslich die sieben `*-adapter-mcp`-Module und `arknet-mcp`.
Das ist die Abgrenzung zum Shared Kernel (ADR-56): dort nur Modellbegriffe, die mindestens zwei Kontexte tragen, hier die Mechanik, die keiner von ihnen braucht.
Die Kante zeigt in eine Richtung -- dieses Modul kennt den Kernel (`ProjectId`, `LanguageTag`), der Kernel kennt dieses Modul nicht, und `arknet-architecture-tests` (`DependencyRulesTest`, Regeln 11/12) haelt beides fest.
Keine Abhaengigkeit auf `arknet-persistence-support`: nichts hier fasst einen Store an, alle Store-Zugriffe laufen ueber Ports, die der Composition Root bedient.

Das Package heisst `mcpsupport` und nicht `mcp.support`, weil `de.hauschel.arknet.mcp..` in `DependencyRulesTest` den Composition Root bezeichnet (Regel 5) und ein Unterpackage davon als Composition Root mitgelesen wuerde.

**Anker-Aufloesung.**
`ProjectResolver` (`String anchor -> ResolvedProject`, Konstante `ANCHOR_KEY`) ist der Port, ueber den jeder `@McpTool`-Adapter pro Aufruf ermittelt, welches Projekt der Aufruf trifft.
`ProjectId` ist **kein** Prozess-Singleton: ein Daemon bedient alle Projekte der Maschine, also traegt jeder Request seinen Anker mit.
Die Aufloesung ist ein Registry-Nachschlagen auf den ganzen, uninterpretierten Wert -- nichts wird abgeleitet, gekuerzt oder geraten.
Die Implementierung `RegisteredAnchorProjectResolver` in `arknet-mcp` adaptiert dafuer den `ResolveProject`-In-Port des `arknet-project`-BC; die Modell-BCs sehen nur diesen neutralen Port und haengen nie an jenem BC.
Fehlender oder unbekannter Anker ist ein Fehler (`UnresolvedProjectAnchorException`, damit die Uebersetzung der BC-eigenen `UnknownAnchorException` an der Portgrenze stattfindet) -- es gibt keinen Default, keinen Rueckfall auf ein Server-Arbeitsverzeichnis und bewusst auch keine `Optional`-Variante der Methode, die an der Aufrufstelle wieder zum Erfinden eines Fallbacks einladen wuerde.

`ResolvedProject` ist das Ergebnis: Record `id: ProjectId`, `defaultLanguage: String` (nullable), `maintainedLanguages: List<String>` (nie null, moeglicherweise leer) und `label: String` (nullable, gelesen ueber `displayName()` mit Rueckfall auf den Id-Wert).
Alle vier Werte kommen aus demselben Registry-Read, den jeder Tool-Aufruf ohnehin fuer das Routing macht -- ein eigener Lookup-Port je Frage haette je Aufruf einen zweiten Store-Read gekostet, fuer Antworten, die dieser eine schon hielt.
`defaultLanguage` bedient zwei Rollen: lesend waehlt es, welche Sprachvariante ein Lesepfad ohne explizite Anfrage bevorzugt zeigt; schreibend ist es der Fallback, auf den `LanguageTag#resolveWriteLanguage` ein weggelassenes `language`-Argument aufloest.
`maintainedLanguages` sagt etwas anderes: `defaultLanguage` ist ein **Rueckfall** (unter welcher Sprache ein Aufruf ohne eigene landet), `maintainedLanguages` eine **Zusage** (welche Sprachen das Projekt zu fuehren erklaert).
Erst die Zusage macht Unvollstaendigkeit definierbar -- ein Feld mit nur einer Sprache ist gegen einen Rueckfall nicht falsch, nur unvollstaendig.
Zwei nicht-kanonische Konstruktoren setzen `maintainedLanguages` auf leer bzw. `label` auf `null`, damit Aufrufstellen ohne Interesse daran unveraendert bleiben.

**Uebersetzungs-Signal.**
`StaleTranslationHint` beantwortet fuer jedes `*_update`, das ein mehrsprachiges Feld schreibt, die Frage "welche vom Projekt gefuehrte Sprache traegt dieses Feld noch, die dieser Aufruf nicht schreibt" -- ein Hinweisblock an der Antwort, nie eine Ablehnung.
Ein Hinweis entsteht nur, wenn alle vier Eingaben etwas sagen: der Aufruf schreibt eine Sprache, das Projekt fuehrt eine andere (`ResolvedProject#maintainedLanguages`), das Feld trug die geschriebene Sprache schon vorher, und es traegt die andere tatsaechlich.
Traegt es die andere nicht, ist das eine **Luecke**, die `store_check LANGUAGE` meldet, und sie hier "veraltet" zu nennen waere dieselbe Aussage doppelt und einmal falsch.
Trug es die geschriebene Sprache vorher nicht, wird **uebersetzt**, nicht korrigiert: die vorhandene Variante ist die Quelle der Uebersetzung, nichts ist veraltet -- der zweite Aufruf des eigenen Zwei-Aufrufe-Workflows endet ohne Hinweis, und bei einer ADR ausserhalb `PROPOSED` ist genau dieser Write der einzige, den die Textfelder noch annehmen.
Diese dritte Bedingung ist nur am Zustand **vor** dem Write entscheidbar -- danach traegt das Feld die Sprache so oder so.
Darum fragt der In-Adapter den Hinweis vor dem Service-Aufruf ab und haengt ihn nach dessen Rueckkehr an: ein Store-Read mehr, wenn der Write scheitert, und ein Schnappschuss, den ein nebenlaeufiger Schreiber altern lassen kann -- beides traegt ein Hinweis.
Die Formulierung bleibt bewusst bei "dieser Aufruf hat sie nicht geschrieben": der WriteFunnel zeichnet je Write **eine** Revision pro Ressource auf, nie eine pro Literal, also gibt es keine Revision je Sprachvariante, an der sich "aelter" belegen liesse.
Die Feldschluessel jedes Adapters stehen in dessen privater `MULTILINGUAL_FIELDS`-Liste, die `arknet-architecture-tests` (`StaleTranslationFieldsMatchShapesTest`) reflektiv gegen die `sh:uniqueLang`-Properties der ausgelieferten Shapes haelt.

`FieldLanguageLookup` ist der Port fuer das eine, was ein In-Adapter selbst nicht weiss -- welche Tags ein Feld schon traegt.
Er liegt hier und wird im Composition Root ueber den generischen Store-Lesepfad implementiert (`StoreFieldLanguageLookup` in `arknet-mcp`), dieselbe Richtung wie bei `ProjectResolver`: ein Port neben den Adaptern, eine Implementierung im Composition Root, konsumiert von jedem BC-In-Adapter, ohne eine einzige neue Modulkante.
Die Alternative -- je BC ein eigener Lookup-Port samt Out-Adapter-Methode -- waere siebenmal dieselbe Frage in sieben Kopien.
Feldschluessel sind Modell-Feldnamen, also die Local Names der dahinterliegenden Praedikate (`definition`, `title`, `useCaseGoal`, ...), dasselbe Vokabular, unter dem `store_check` seine Sprachluecken meldet; ein Feld, dessen Werte auf einer besessenen Kind-Ressource liegen (Akzeptanzkriterium, Use-Case-Schritt, ADR-Konsequenz), wird unter der **Kante** gefuehrt, die es besitzt (`acceptanceCriterion`, `mainStep`, `consequence`), weil der Aufrufer solche Listen geschlossen schreibt.
Genau dieses Poolen ist die eine Stelle, an der die Vor-dem-Write-Momentaufnahme nicht traegt: ein Write laesst die Varianten anderer Sprachen eines **direkten** Literalfeldes stehen, aber derselbe `*_update` kann ein Kind anlegen und ein anderes entfernen, und mit dem entfernten faellt womoeglich der letzte Traeger einer Sprache weg, den die Momentaufnahme schon gezaehlt hat.
Aufgeloest wird das beim Aufrufer, nicht im Mechanismus: ein Tool meldet eine Kind-Kante nur dann als geschrieben, wenn derselbe Aufruf unter ihr nichts entfernt.
Die zweite Port-Methode `ofProjectRegistration(String projectLabel)` ist kein Sonderfall aus Bequemlichkeit: der Registry-Record eines Projekts liegt im reservierten System-Dataset, dessen Id `ProjectId` per Konstruktion nicht halten darf.

**Antwortform der schreibenden Tools.**
`WriteResponse` ist der gemeinsame Renderer der drei Bestandteile jeder schreibenden Tool-Antwort: `withProject(body, project)` haengt die abschliessende Zeile `project: <name>` an (ein vergessener `projectAnchor` schreibt sonst unsichtbar ins Anker-Projekt der Sitzung), `linked(...)`/`unlinked(...)` rendern die Kurzbestaetigung einer Kantenoperation (`linked FR-3 -> TERM-7 (usesTerm)`, der Aufrufer haelt beide Enden schon), `listFieldDiff(field, before, after)` die Diff-Zeile eines Wholesale-Listenfeldes (`usesTerm: removed TERM-22, added TERM-9`) und `countDiff(field, removed, added)` deren Zaehlvariante fuer Listen ohne stabile, vom Aufrufer getippte Eintragsidentitaet (Use-Case-Schritt, ADR-Konsequenz, Akzeptanzkriterium).
Der Diff wird aus dem Zustand des Feldes vor und nach dem Write berechnet, nie aus dem Request: ein wholesale ersetztes Feld verliert, was der Aufrufer zu wiederholen vergass, und gerade das steht im Request nicht.
Ein unveraendertes Feld liefert die leere Zeichenkette und kostet keine Zeile.
Liegt zentral und nicht je Adapter, aus demselben Grund wie `StaleTranslationHint`: dreizehn In-Adapter bauen ihre Antwort selbst zusammen, und eine Form, die in jedem anders aussaehe, koennte ein Agent nicht einmal lernen.

**Tool-Parameter-Texte.**
`ToolParameterDescriptions` traegt die kurzen `@McpToolParam`-Beschreibungstexte fuer `projectAnchor`, `language` und `displayLocale` -- der querschnittliche Teil des Tool-Schemas jedes `*-adapter-mcp`-Moduls.
Sie sind bewusst kurz und sagen nur, *wann* ein Aufrufer den Parameter setzt; die ausfuehrliche Erklaerung (Fallback-Ketten, der `X-Arknet-Project-Anchor`-Header, wie eine Anzeigesprache gewaehlt wird) steht einmal in `arknet-mcp`s `spring.ai.mcp.server.instructions`.
Der Grund fuer die eine Quelle ist Payload: Spring AI inlint jedes Parameter-Schema je Tool statt eines `$ref`, und die frueher je Adapter ausformulierte `projectAnchor`-Beschreibung machte allein 19,7 % der `tools/list`-Antwort des Servers aus.
