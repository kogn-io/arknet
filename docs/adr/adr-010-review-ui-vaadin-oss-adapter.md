# ADR-010: Review-UI als read-only Vaadin-OSS-Adapter

- Status: Proposed (2026-07-20) -- wird Accepted, sobald das UI-Modul geschnitten und gebaut wird
- Verwandt: ADR-002, ADR-004, ADR-006, ADR-008

## Kontext

arknet hat heute zwei Kanaele auf den Store: die schreibenden MCP-Tools (`req_*`/`term_*`/
`uc_*`/`bc_*`, Authoring ueber Claude Code, store-first nach ADR-005) und den generischen,
lesenden Store-Pfad (`store_overview`/`resource_get` und die Traceability-Tools, ADR-006).
Beide bedienen einen KI-Agenten. Es fehlt eine menschenlesbare, navigierbare Durchsicht des
Modells fuer Nutzer, die **nicht** ueber Claude Code arbeiten: ein Fachbereich / Product Owner,
der Requirements und Use Cases fachlich reviewen und freigeben soll, und ein Architekt, der
Nachverfolgbarkeit (Requirement <-> Use Case, verwendete Begriffe, verwaiste Referenzen)
nachvollziehen will.

Eine UI-Technologie fuer diesen Review-Kanal war zu waehlen. Die Rahmenbedingungen setzen enge
Kraefte: arknet ist ein reiner Java/Spring/hexagonaler Stack ohne zweite Sprache; es ist ein
lokaler Single-User-Client (ADR-001); es folgt einem Open-Core-Modell, dessen Community Edition
aus OSS-Bausteinen baubar bleiben muss (ADR-002). ADR-004 hat fuer den MCP-Layer bereits eine
Technologie-Linie gesetzt statt sie pro Modul zu bewerten -- dieselbe Frage stellt sich hier
fuer die UI.

## Entscheidung

**Vaadin (Flow) ist die Technologie-Linie fuer arknets Review-UI** -- gesetzt, nicht pro
Screen bewertet.

1. Die UI wird server-seitig in Java mit Vaadin Flow gebaut. Keine zweite Sprache, kein
   JS-Build-Toolchain im Reaktor.

2. **Ausschliesslich OSS-Komponenten (Vaadin Flow, Apache 2.0). Kein Vaadin Pro/Commercial**
   (Charts, Board, Grid-Pro, Rich Text Editor Pro). Grund: die Community Edition muss aus
   OSS-Abhaengigkeiten baubar und auslieferbar bleiben (ADR-002) -- ein proprietaeres
   UI-Toolkit unter der OSS-CE waere ein Wertungswiderspruch.

3. Die UI ist ein weiterer **treibender Adapter auf demselben Composition Root**, der
   in-process ueber die bestehenden Lese-In-Ports liest -- die `*_list`/`*_get`-In-Ports der
   vier BCs und den generischen Lesepfad aus ADR-006 (`store_overview`/`resource_get`/
   `trace_matrix`/`orphan_check`/`impact_analysis`). Nicht ueber das MCP-Protokoll: MCP bedient
   den treibenden Agenten, nicht eine Komponente im selben Prozess. Damit ist die UI der dritte
   Convenience-Layer auf demselben Kern, neben MCP-Server und dem in der Produktvision
   vorgesehenen CLI.

4. **Read-only.** Die UI schreibt nicht in den Store und wird kein zweiter Schreibpfad neben
   den store-first-Write-Tools (ADR-005) -- ein zweiter Schreibpfad wuerde das SHACL-Write-Gate
   duplizieren und den einen Modell-Lebenszyklus aufspalten. Authoring bleibt bei den
   MCP-Write-Tools. Ob die UI je Authoring bekommt, ist deferred (siehe Konsequenzen).

## Konsequenzen

**Positiv:**

- Kein zweiter Sprach-/Build-Stack: die UI bleibt im vorhandenen Java/Spring/Hexagonal-Skillset
  und -Build.
- Die UI konsumiert die bestehenden Lese-In-Ports statt eines eigenen Store-Zugriffs; die
  Composition-Root-Reinheit aus ADR-006 traegt weiter (RDF4J bleibt in den
  `*-adapter-kogniordf`-Modulen), und der Adapter-komponiert-ueber-In-Ports-Schnitt aus ADR-008
  deckt einen lesenden Adapter, der mehrere BC-In-Ports fuer die Anzeige zieht, bereits ab.
- OSS-only haelt die Community Edition baubar und als OSS auslieferbar (ADR-002).

**Negativ / bewusst deferred (YAGNI):**

- Das UI-tragende Modul wird eine Spring-Boot-Webanwendung und damit schwerer. Das
  Spring-Boot-Stack-Alignment, das ADR-004 als Kost benennt (rdf4j-BOM gegen Spring-Boot-Stack
  bei SLF4J/Logback/jackson), wird mit einem zweiten Spring-Boot-Modul draengender und muss
  zentral getragen werden.
- OSS-only schliesst Vaadin-Pro-Komponenten aus. Eine vollstaendige interaktive
  Graph-Visualisierung (Vaadin-Charts-Kandidat) ist damit nicht out-of-the-box verfuegbar --
  bewusst in Kauf genommen: der Review-Kanal zeigt Vernetzung ueber verlinkte Referenzen und
  eine kleine Nachbar-Ansicht je Detail, nicht als eigenen Vollgraph-Screen.
- Der als Vorlage dienende Design-Mockup (HTML/CSS/JS) laesst sich nicht 1:1 in Vaadins
  Komponentenmodell uebernehmen. Er liefert Informationsarchitektur und visuelle Sprache
  (Tokens, Dichte, Farbwelt), nicht Code zum Kopieren -- Stack-Konsistenz wurde bewusst ueber
  pixelgenaue Mockup-Treue gestellt.
- Der Modulschnitt -- eigenes Maven-Modul (z.B. `arknet-review-ui`) gegen einen Teil von
  `arknet-mcp` -- ist **bewusst offen gelassen**. Das ist Freds Entscheidung beim Bau, keine
  dieser ADR.
- Authoring ueber die UI ist deferred, mit benannter Vorbedingung: es braeuchte ein
  Goal-Mint-Tool, die restlichen Requirement-Status als setzbare Werte sowie `scopedTo` und ein
  grobes `satisfies` als Eingabefelder. Bewusst offen, bis der Bedarf konkret ist -- ein
  separates Vorhaben, kein Teil dieses Review-MVP.

## Alternativen

- **React / JS-Toolchain (z.B. mit Tailwind).** Direkterer Weg vom Design-Mockup zu
  pixelgenauem Code. Zieht aber eine zweite Sprache und einen JS-Build in ein sonst reines
  Java-Projekt -- ein dauerhafter Stack-Bruch fuer eine reine Review-UI. Verworfen zugunsten
  der Stack-Konsistenz.
- **Vaadin Pro/Commercial.** Liefert Charts (Vollgraph) und Pro-Grids out-of-the-box. Ein
  proprietaeres UI-Toolkit unter der OSS-Community-Edition widerspricht dem Open-Core-Modell
  (ADR-002). Verworfen.
- **Keine eigene UI -- das generierte `store-report.html` (ADR-006) genuegt.** Verworfen: der
  Store-Report ist ein flacher Digest/HTML-Dump zum Nachschlagen, keine navigierbare,
  filterbare Review-Arbeitsflaeche (Master-Detail, Traceability-Matrix, Status-/Prio-Filter) --
  er bedient den Review-Job des PO nicht.
- **UI liest ueber das MCP-Protokoll (als MCP-Client) statt in-process.** Verworfen: ein
  unnoetiger Netz-/Protokoll-Hop fuer eine Komponente im selben Prozess und Composition Root;
  MCP ist fuer den treibenden Agenten da, nicht fuer einen internen Lesekanal.
