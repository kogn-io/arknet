# neu-08: EIN geteilter HTTP-Daemon auf Loopback statt eines Subprozesses je Session

- Status: Proposed (2026-08-23)

## Kontext

Der uebliche MCP-Betrieb ist ein stdio-Subprozess pro Client-Session. Das funktioniert,
solange ein Server nur eine Session bedient. arknets Store ist ein Verzeichnis mit einem
Schreib-Lock: zwei Sessions desselben Projekts -- zwei Terminals, ein Worktree daneben --
starten zwei Subprozesse, und der zweite scheitert am Lock des ersten.

Ein Daemon je Projekt loest das nicht, sondern verschiebt es: dann braucht jedes Projekt einen
eigenen Port und einen eigenen Starteintrag.

## Entscheidung

arknet-mcp laeuft als **ein** langlebiger Daemon, erreichbar ueber Streamable HTTP auf
`127.0.0.1:47331`, und bedient **alle** Projekte der Maschine.

1. Transport ist dieselbe Spring-AI-Linie aus neu-07, nur der HTTP- statt der
   stdio-Baustein.
2. Ein Prozess haelt die Stores aller Projekte. Jede Repository-Methode erwirbt ihr Dataset
   pro Aufruf ueber eine explizite ProjectId -- ein Prozess verwaltet damit mehrere Datasets
   unter einem Storage-Root ohne Lock-Konflikt.
3. Welches Projekt ein Aufruf trifft, entscheidet der Anker, den der Client pro Aufruf
   mitschickt (neu-09). Nicht der Startzustand des Servers.
4. **Loopback allein ist keine Vertrauensgrenze, solange sie nicht geprueft wird.** Spring AI
   ruft zwar einen Transport-Security-Validator auf, verdrahtet aber standardmaessig einen,
   der nichts tut. Ohne echten Origin-/Host-Check waere ein per DNS-Rebinding umgebogener
   Hostname same-origin mit dem Daemon und koennte jedes Tool fahren, obwohl der Aufruf von
   einer Webseite kommt. arknet ersetzt den Default darum durch einen Validator mit
   Allowlist.

## Konsequenzen

**Positiv:** Beliebig viele Sessions und Worktrees desselben Projekts arbeiten gleichzeitig.
Ein Starteintrag statt einem pro Projekt.

**Negativ:** Der Daemon aktualisiert sich bei einem Release nicht selbst -- nach einem Bau
laeuft weiter der alte Stand, bis jemand neu startet, und der Fehler sieht aus wie ein
Bug im Tool. Ein Absturz trifft alle Projekte gleichzeitig statt nur eines. Und ein
langlebiger Prozess auf einem festen Port ist eine dauerhaft offene lokale Flaeche, die der
Subprozess nicht war -- Punkt 4 ist deshalb kein Detail, sondern die Bedingung, unter der
diese Entscheidung tragbar ist.

## Alternativen

- **stdio-Subprozess je Session.** Verworfen -- kollidiert am Store-Lock.
- **Ein Daemon je Projekt.** Verworfen -- verschiebt das Problem in die Portverwaltung.
- **Store ohne Verzeichnis-Lock.** Nicht in arknets Hand; Eigenschaft des Substrats.
