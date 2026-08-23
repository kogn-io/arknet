# neu-09: Projekt-Identitaet wird registriert, nicht abgeleitet

- Status: Proposed (2026-08-23) -- ersetzt ADR-016

## Kontext

Wenn ein Daemon alle Projekte bedient (neu-08), muss jeder Aufruf sagen, welches Projekt er
meint. Der naheliegende Weg ist Ableitung: der Client schickt sein Arbeitsverzeichnis, der
Server macht daraus eine Identitaet -- ueber den git-Wurzelpfad oder einen Hash.

Ableitung bricht auf drei Wegen: ein Worktree hat ein anderes Verzeichnis als sein
Hauptrepository; ein verschobenes Projekt bekommt eine neue Identitaet und verliert seine
Daten; und ein Client ohne git hat nichts, woraus abzuleiten waere. Jeder dieser Faelle
erzeugt still ein neues, leeres Projekt statt eines Fehlers.

## Entscheidung

Projekt-Identitaet wird registriert. Der Store-Gegenstand ist das Projekt.

1. **Ein Dataset haelt die Daten genau eines Projekts.** Es gibt keine Ebene dazwischen. Der
   Begriff "Workspace" existiert nicht -- weder als Store-Gegenstand noch als Name im Code.
2. **Der Client sendet einen Anker, der Server interpretiert ihn nie.** Ein Anker ist eine
   opake Zeichenkette mit Typ (`path`, `url`, `uuid`). Ob er wie ein Pfad aussieht, ist Sache
   des Clients; der Server schlaegt ihn nur nach. Uebertragen wird er per HTTP-Header oder --
   fuer Clients ohne Header-Kontrolle -- als expliziter Tool-Parameter.
3. **Anker und ProjectId sind zwei Dinge.** Der Anker ist der Haltepunkt, an dem sich ein
   Client festmacht; die ProjectId ist die Identitaet, die der Server fuehrt. Darum zeigen
   mehrere Anker auf dasselbe Projekt, ohne es zu verdoppeln.
4. **Unbekannter Anker ist ein Fehler, kein Default.** Ein Aufruf ohne oder mit unbekanntem
   Anker scheitert mit einer Meldung, die auf die Projektanlage verweist. Kein impliziter
   Rueckfall auf ein Server-Arbeitsverzeichnis: ein Schreibvorgang ohne geklaerte
   Zugehoerigkeit ist genau der Fehler, den diese Entscheidung ausschliesst.

Die Registry selbst ist nicht projekt-scoped und wohnt in einem reservierten System-Dataset.

## Konsequenzen

**Positiv:** Worktrees, verschobene Verzeichnisse und Clients ohne git funktionieren gleich.
Ein Projekt kann umziehen, ohne seine Daten zu verlieren -- es bekommt einen zweiten Anker.

**Negativ:** Ein Projekt muss angelegt werden, bevor irgendetwas geschrieben werden kann. Der
erste Kontakt mit arknet ist damit ein Registrierungsschritt, kein Ergebnis -- und wer die
Meldung nicht liest, haelt das fuer einen Fehler.

## Alternativen

- **Identitaet aus dem Verzeichnis ableiten.** Verworfen -- bricht bei Worktree, Umzug und
  Nicht-git und erzeugt dabei stille Leerprojekte.
- **Default-Projekt als Rueckfall.** Verworfen -- schreibt Daten an einen Ort, den niemand
  gewaehlt hat.
