# ADR-020: Ein breit gefasster Actor statt eines eigenen Stakeholder-Typs

- Status: Proposed (2026-08-31) -- wird Accepted, sobald geklaert ist, ob eine Rolle
  eine eigene Ressource wird und der Actor damit ein reiner Traeger bleibt
- Verwandt: ADR-017

## Kontext

Die Standarddefinition des RE-Grundlagenstoffs fasst ein Requirement als Stakeholder-Need.
Zwei der klassischen Validierungskriterien (IEEE-830-/Davis-Liste) haengen direkt daran:
*correct* -- bildet jedes Requirement tatsaechlich einen Stakeholder-Need ab, oder ist es
selbst ausgedacht -- und *complete* -- fehlt kein Need. Ohne einen Begriff fuer den
Interessentraeger sind beide prinzipiell nicht pruefbar: die Frage "wessen Bedarf war das?"
hat kein Ziel (#322).

Die naheliegende Antwort waere ein eigener Typ `Stakeholder`. Sie hat einen Preis, der erst
beim Hinsehen auffaellt: dieselbe reale Person oder Organisation existierte dann zweimal im
Modell -- einmal als Interaktionsakteur eines Use Case, einmal als Interessentraeger --
ohne Bruecke zwischen beiden. Ein Endnutzer ist beides.

Der bestehende Actor-Begriff steht dem naeher, als seine Definition zugibt.
`arkproc:Actor` ist als "Person oder System, das an einem Prozess beteiligt ist"
deklariert, seine Unterklassen `HumanActor`/`SystemActor`/`LegalActor` sind aber bereits
eine Typologie von Handelnden ueberhaupt, nicht von Prozessteilnehmern. Und RDF erlaubt
einer Ressource mehrere Typen und Beziehungen -- das Duplikat ist keine Notwendigkeit des
Datenmodells, sondern eine Folge des zu engen Begriffs.

Ein dritter Auftritt derselben Sache steht bereits im Raum: der `prov:Agent`, der eine
Aenderung vorgenommen hat.

## Entscheidung

Es gibt keinen Typ `Stakeholder`. Stattdessen wird der Actor-Begriff breit gefasst.

1. `arkproc:Actor` bezeichnet eine Instanz, die handeln **oder** Interessen haben kann.
   Die Beteiligung an einem Use Case ist damit eine von mehreren moeglichen Rollen, nicht
   mehr die Definition.
2. Rollen werden ueber Kanten ausgedrueckt, nicht ueber Marker am Actor selbst: dass ein
   Actor Interessentraeger eines Requirements ist, sagt die Herkunftskante; dass er in
   einem Ablauf handelt, sagt die Use-Case-Kante. "Stakeholder" ist damit eine Rolle, keine
   Klasse.
3. Die Typologie wird um die Gruppe ohne Rechtsform ergaenzt -- Fachbereich, Gremium -- und
   an `prov:Agent` angeschlossen, damit der schreibende Agent spaeter an denselben Begriff
   andocken kann, ohne dass diese Frage hier vorweggenommen wird.

## Konsequenzen

**Positiv:** Eine reale Person oder Organisation ist genau eine Ressource im Modell, egal
in wie vielen Rollen sie auftritt -- das Duplikat entfaellt an der Wurzel statt ueber eine
nachtraegliche Identitaetsbruecke. *correct* und *complete* werden pruefbare Fragen. Eine
Rolle laesst sich hinzufuegen, ohne einen neuen Ressourcentyp einzufuehren, weil sie nur
eine weitere Kante ist.

**Negativ / bewusst deferred (YAGNI):** Der Begriff heisst weiter "Actor" und wohnt weiter
im Prozess-Namensraum, obwohl die Prozessbeteiligung nur noch eine Rolle unter mehreren
ist -- ein Namensraumwechsel erzwaenge eine Datenmigration bestehender Typ-Tripel und
waere der teurere Fehler. Wer aus der RE-Literatur kommt, sucht die Klasse "Stakeholder"
und findet sie nicht; die Rolle traegt den Begriff, nicht das Vokabular. Ob der
schreibende `prov:Agent` und der modellierte Actor dieselben Instanzen sind, bleibt offen
-- der Anschluss ist vorbereitet, die Frage nicht beantwortet.

## Alternativen

- **Eigener Typ `Stakeholder` neben dem Actor.** Verworfen -- er nimmt die Doppelexistenz
  derselben realen Person in Kauf und macht die Bruecke zwischen ihren Auftritten zu einer
  spaeteren Zusatzaufgabe, statt sie ueberfluessig zu machen.
- **Stakeholder als Marker am Actor, nach dem Muster der bestehenden Actor-Facette.**
  Verworfen -- ein Marker sagt "ist irgendwo Interessentraeger", die Kante sagt "wovon".
  Beide nebeneinander koennen auseinanderlaufen: Marker ohne Kante, Kante ohne Marker.
- **Herkunft gar nicht modellieren.** Verworfen -- damit bleiben *correct* und *complete*
  dauerhaft unpruefbar, was der Anlass der Entscheidung war.
