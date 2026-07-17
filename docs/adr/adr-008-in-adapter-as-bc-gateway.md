# ADR-008: In-Adapter als Tor zum Bounded Context, nicht Teil davon

- Status: Accepted (2026-07-17)
- Verwandt: ADR-006 (generischer Store-Lesepfad -- dieselbe Grundfrage "wo lebt geteilte
  Logik", andere Antwort), ADR-007 (geteiltes Write-Gate -- der Nachbarfall auf der
  Schreib-/Technik-Seite)
- Issue: #77

## Kontext

Seit #77 traegt `TermRef` (requirements-BC) die opake `ResourceId` des referenzierten
Glossar-Begriffs statt dessen Business-Codes (`TERM-N`). Der MCP-Nutzer tippt weiterhin
`TERM-1` in `req_link_term` -- die Aufloesung Code -> Identitaet sitzt sauber hinter dem
neuen Out-Port `TermLookup`, entkoppelt von der ubiquitous-language-BC (req-core kennt nur
den Kernel-Typ `ResourceId`, nie einen `ul`-Typ).

Der Rueckweg fehlte: `req_get`/`req_list` sollen dem Nutzer wieder `TERM-N` zeigen, nicht die
nackte IRI (die er nicht zurueck eingeben kann). Nur die ubiquitous-language-BC kennt die
Abbildung `ResourceId -> TermCode` -- sie besitzt den Term. Zu entscheiden war: darf der
In-Adapter der requirements-BC (`arknet-requirements-adapter-mcp`) einen In-Port der
ubiquitous-language-BC aufrufen, um diese Auskunft zu bekommen? Bislang stand in
`arknet-architecture-tests`' `DependencyRulesTest`-Javadoc unwidersprochen: "no bounded
context depends on another [ist] already enforced [...] by Maven [...], because the forbidden
module is simply not on the core's compile classpath" -- eine Aussage, die keine Instanz je
auf die Probe gestellt hatte.

## Entscheidung

1. **Ja, ein In-Adapter darf den In-Port einer Nachbar-BC konsumieren.** Ein In-Adapter ist
   das Tor zu seinem eigenen Hexagon, nicht Teil von dessen Core. Die Invariante "kein BC
   haengt an einem anderen" bindet die `*-core`-Module (Domaene + In-/Out-Ports); die Adapter
   drumherum duerfen komponieren. `arknet-requirements-adapter-mcp` haengt seither von
   `arknet-ubiquitous-language-core` ab -- die erste Cross-BC-Dependency ueberhaupt, und rein
   lesend: sie liefert nur Anzeige-Auskunft, sie schreibt nichts und `req_link_term`s eigener
   Schreibpfad bleibt unveraendert ueber `TermLookup` entkoppelt.

2. **Der neue In-Port traegt seinen Vertrag als Teil der Entscheidung, nicht als Implementierungsdetail.**
   `ResolveTerms#getById(WorkspaceId, ResourceId...)` wirft nie; eine nicht aufloesbare Id
   fehlt einfach im Ergebnis. Damit entsteht die eigentliche Invarianz, die dieser Umbau
   braucht -- "nie im Pflichtteil einer Anzeige, immer Fallback auf die IRI" -- **von selbst**,
   statt an jeder Aufrufstelle diszipliniert eingehalten werden zu muessen. Das ist der Grund,
   warum diese Loesung dem in #77 zunaechst skizzierten `OPTIONAL`-Join im req-Adapter
   ueberlegen ist: ein `OPTIONAL`-Join haette denselben Fallback nur so lange geliefert, wie
   niemand vergisst, ihn `OPTIONAL` zu halten -- Geschmack ist das nicht, sondern eine
   strukturelle Eigenschaft des gewaehlten Vertrags.

## Warum nicht die Alternativen

- **Reverse-Lookup im req-eigenen `TermLookup`-Out-Port.** Haette funktioniert und zum
  etablierten Muster gepasst (`TermLookup` existiert bereits fuer die Gegenrichtung
  Code -> Identitaet). Zementiert aber, dass req per Raw-SPARQL in ul's Graph hineinliest,
  statt die besitzende BC zu fragen -- genau das tut die uc-BC heute noch
  (`KognioRdfUseCaseRepository#readBySubject`/`#readSupportingActors` lesen mandatory in den
  Terms-Graph fuer Actor-Label), und es ist dort ein offener Punkt an #77, kein Vorbild.
- **Ein Praedikat-Wert zurueck in `TermRef`** (z.B. den Code als zweites Feld neben der
  `ResourceId` mitfuehren). Ginge direkt gegen #77s eigene Diagnose: die Kante soll die
  Identitaet sein, kein von einem Praedikat abgeleiteter Wert (siehe `TermRef`s Javadoc). Und
  es haette die Idempotenz von `req_link_term` gebrochen -- `RequirementService:117`
  (`current.usesTerms().contains(term)`) haengt an `TermRef.equals`; ein zweites
  Record-Feld geht automatisch in `equals` ein, also waeren derselbe Term mit und ohne
  mitgefuehrten Code *ungleich*. Genau im Degradationsfall (Code nicht aufloesbar) waere ein
  erneutes `req_link_term` desselben Terms dann kein No-op mehr, sondern ein Duplikat.

## Abgrenzung zu ADR-006 und ADR-007

Alle drei ADRs beantworten "wo lebt eine BC-uebergreifende Zustaendigkeit", aber keine zwei
dieselbe Frage:

| | ADR-006 (Store-Report) | ADR-007 (Write-Gate) | ADR-008 (diese ADR) |
|---|---|---|---|
| Was wird geteilt | generische, BC-**neutrale** Logik (`?s ?p ?o`) | BC-neutrale Technik (SHACL-Validierung) | eine BC-**spezifische** Fremdauskunft |
| Wohin | Composition Root | eigenes Modul | bleibt im aufrufenden In-Adapter |
| Warum dort | einziger Konsument sieht ohnehin alles | mehrere Adapter, die einander nicht sehen duerfen | der Aufrufer *ist* der einzige, der die Auskunft fachlich braucht |

ADR-006 hat generische Logik, die **keine** BC kennt, ins Composition Root verwiesen, weil sie
dort ohnehin von allem gebraucht wird. Hier ist das Gegenteil der Fall: `req_get` ist
zutiefst requirements-spezifisch (Format, Felder, Statusmodell) und holt sich nur eine
einzelne Fremdauskunft dazu -- das ins Composition Root zu ziehen waere entweder ein
generisches Tool ohne fachlichen Sinn, oder es wuerde `RequirementMcpTools`-Logik ins
Composition Root zerren, was ADR-006 selbst vermeidet ("`ArknetMcpConfiguration` bleibt
reines Bean-Wiring"). ADR-007 ist der Nachbarfall auf der Schreib-/Technik-Seite: dort
brauchten *mehrere* Adapter, die einander nicht sehen duerfen, denselben Baustein -- die
Antwort war ein eigenes, technologieneutrales Modul, gerade **kein** Shared Kernel (den
duerfen die `*-core` sehen, das Gate nicht). Hier gibt es nur einen Konsumenten
(`arknet-requirements-adapter-mcp`) einer einzigen Nachbar-BC -- kein Baustein, der geteilt
werden muesste, sondern eine direkte Adapter-zu-In-Port-Dependency. Kein eigenes Modul, kein
Composition Root: die Antwort liegt dort, wo die Frage gestellt wird.

## Konsequenzen

**Positiv:**

- `req_get`/`req_list` zeigen wieder `TERM-N` statt einer nicht wieder eingebbaren IRI, ohne
  dass die requirements-BC (Core oder Out-Adapter) von der ubiquitous-language-BC abhaengt --
  die Abhaengigkeit ist auf den In-Adapter begrenzt und rein lesend.
- Der Nie-wirft-Vertrag von `ResolveTerms` macht den Degradationsfall strukturell sicher statt
  diszipliniert einzuhalten (siehe Entscheidung 2).
- Ein Praezedenzfall fuer #66 (bounded-context-Zuordnung) und die noch ausstehende uc-Haelfte
  von #77: beide brauchen dieselbe Art Fremdauskunft und muessen sie nicht mehr neu
  herleiten.

**Negativ / bewusst:**

- **"Kein BC haengt an einem anderen" gilt nicht mehr pauschal.** Sie ist auf die `*-core`
  praezisiert (`CLAUDE.md`, `DependencyRulesTest`-Javadoc). Der Modulschnitt traegt die
  praezisierte Invariante weiterhin allein (keine `*-core`-POM deklariert eine
  Fremd-BC-Abhaengigkeit) -- ob die *neue*, engere Formulierung eine eigene ArchUnit-Regel
  verdient, ist **bewusst offen gelassen**. Das ist Freds Entscheidung, keine dieser ADR.
- **Die requirements-Komponente ist ohne die ubiquitous-language-Komponente nicht mehr
  vollstaendig deploybar.** Fuer arknet folgenlos -- ein Prozess, ein Composition Root, beide
  BCs sind ohnehin immer zusammen im selben `arknet-mcp` gebunden. Der Distributions-Schnitt
  (welche BCs zusammen ausgeliefert werden) ist laut Projektstand ohnehin offen und soll,
  wenn er faellt, entlang der **Editionsgrenze** (Community/Closed, ADR-002/ADR-003) verlaufen,
  nicht entlang einzelner BCs -- diese Entscheidung nimmt dem nichts vorweg.
- **Der Praezedenzfall bindet.** Naechste Cross-BC-Anzeige-Bedarfe (uc-Haelfte von #77, #66)
  sollten denselben Schnitt waehlen (In-Adapter -> fremder In-Port, nie-wirft-Batch-Port),
  nicht wieder Raw-SPARQL in einen fremden Graphen. Wo das nicht befolgt wird (uc's
  `readBySubject`/`readSupportingActors` heute), ist es eine offene Altlast, keine zulaessige
  zweite Bauart.

## Alternativen

- **Reverse-Lookup im `TermLookup`-Out-Port der requirements-BC.** Siehe "Warum nicht die
  Alternativen" oben -- verworfen, weil es die Cross-BC-Entkopplung nur verschiebt (Raw-SPARQL
  in fremden Graphen statt eines fachlichen Aufrufs) statt sie einzuhalten.
- **Praedikat-Wert (Code) zusaetzlich in `TermRef` mitfuehren.** Verworfen: bricht
  `req_link_term`s Idempotenz im Degradationsfall (siehe oben) und widerspricht #77s
  eigener Diagnose (Identitaet statt abgeleitetem Praedikat-Wert).
- **Generisches Anzeige-Tool im Composition Root, analog ADR-006.** Verworfen: `req_get`s
  Format ist requirements-spezifisch, kein generischer, BC-neutraler Baustein -- das waere ein
  Composition Root, das BC-Logik traegt, und widerspraeche ADR-006s eigenem Prinzip
  ("`ArknetMcpConfiguration` bleibt reines Bean-Wiring").
- **Eigenes Modul fuer die Anzeige-Aufloesung, analog ADR-007.** Verworfen: es gibt nur einen
  Konsumenten (der req-In-Adapter) einer einzigen Nachbar-BC -- ADR-007s Leitregel ("ein
  geteilter Baustein bekommt erst dann ein eigenes Modul, wenn er von mehreren Modulen
  gebraucht wird, die einander nicht sehen duerfen") greift hier nicht: es gibt nichts zu
  teilen, nur eine direkte Abhaengigkeit.
