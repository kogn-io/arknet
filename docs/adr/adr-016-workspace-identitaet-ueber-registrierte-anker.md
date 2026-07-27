# ADR-016: Workspace-Identitaet ueber registrierte, typisierte Anker statt abgeleiteter Namen

- Status: Proposed (2026-07-27)
- Verwandt: ADR-001 (loest die dort festgehaltene Herkunft der WorkspaceId ab -- Aufloesung
  beim Serverstart ueber eine Slug-Kette), ADR-009 (loest dessen Punkt 3 ab -- Ableitung der
  WorkspaceId aus dem Herkunftsverzeichnis per git-common-dir; Transport, Betriebsmodell und
  Vertrauensgrenze bleiben unveraendert gueltig), ADR-005 (Store-first: die Registry lebt im
  Store, nicht in einer Betreiber-Konfiguration), ADR-006 (generischer Lesepfad, der das
  Registry-Dataset ausblenden muss), ADR-007/ADR-013/ADR-014 (SHACL-Write-Gate, WriteFunnel
  und Head-als-Concurrency-Token, ueber die die Registry geschrieben wird)

## Kontext

Die WorkspaceId wurde bisher aus dem Herkunftsverzeichnis des Clients *abgeleitet*: geslugter
Basename des git-Ankers, sonst des Arbeitsverzeichnisses. Eine Ableitung ist nur so gut wie die
Information, die der Server hat -- und er hat keine. Er erhaelt eine Zeichenkette und kann sie
weder pruefen noch anreichern. Der Basename wirft den Pfad weg, also fallen zwei gleichnamige
Projekte an verschiedenen Orten auf dieselbe Id, dasselbe Dataset und dasselbe
Store-Verzeichnis: stille Vermischung zweier Architekturmodelle, ohne Fehlermeldung und ohne
erkennbares Symptom, bis Business-Codes und Cross-Referenzen projektuebergreifend kollidieren.

Der git-Anteil der Ableitung traegt zudem nicht, wo er gebraucht wird. Er setzt voraus, dass der
Serverprozess das Projektverzeichnis sieht und `git` ausfuehren kann. Fuer nicht-git-Projekte
existiert er ohnehin nicht; sie fallen seit jeher auf den Basename des Startverzeichnisses
zurueck, mit demselben Kollisions- und Fragmentierungsverhalten. arknet ist ausserdem
ausschliesslich eine Server-Anwendung -- lokal betrieben oder spaeter im Netz -- und nie ein im
Projektverzeichnis mitlaufender Prozess. Ein Betriebsmodus, in dem der Server das Projekt selbst
inspizieren koennte, ist damit nicht bloss unpraktisch, sondern nicht vorgesehen.

Damit steht die Frage nicht mehr, *wie* aus einem Verzeichnis eine Id abgeleitet wird, sondern
ob abgeleitet werden darf. Zusaetzliche Kraft: ein Workspace ist nicht deckungsgleich mit einem
Projekt. Ein Nutzer fuehrt ein Architekturmodell, das mehrere Repositories umfasst (Frontend,
Backend, Infrastruktur), und arbeitet zugleich aus mehreren Verzeichnissen desselben
Repositories (Worktrees). Jede Ableitung aus *einem* Verzeichnis muss diese Vielfalt entweder
verlieren oder erraten.

## Entscheidung

Workspace-Identitaet wird **registriert, nicht abgeleitet**.

1. **Der Client sendet einen Anker, der Server interpretiert ihn nicht.** Ein Anker ist eine
   opake Zeichenkette mit einem Typ (`path`, `url`, `uuid`, spaeter weitere). Ob er wie ein
   Dateipfad aussieht, ist Sache des Clients; der Server schlaegt ihn ausschliesslich nach.
   Uebertragen wird er per HTTP-Header oder -- fuer Clients ohne Header-Kontrolle -- als
   expliziter Tool-Parameter. Beide Wege stehen jedem MCP-Client offen und setzen weder eine
   Client-Capability noch git voraus.

2. **Unbekannter Anker ist ein Fehler, kein Default.** Ein Aufruf mit unbekanntem oder ohne
   Anker scheitert mit einer Meldung, die auf die Anlage eines Workspace verweist. Es gibt
   keinen impliziten Default-Workspace und keinen Rueckfall auf ein Server-Arbeitsverzeichnis:
   ein Schreibvorgang ohne geklaerte Zugehoerigkeit ist genau der Fehler, den diese Entscheidung
   ausschliesst.

3. **Ein Workspace haelt mehrere Anker; ein Anker gehoert zu genau einem Workspace.** Damit
   umfasst ein Workspace mehrere Projekte, Repositories und Verzeichnisse, ohne dass der Server
   ihre Beziehung erraten muss. Die Eindeutigkeit des Ankers ueber alle Workspaces hinweg ist
   die zentrale Invariante des Modells.

4. **Identitaet und Beschriftung sind getrennt.** Die WorkspaceId ist opak und wird nie
   interpretiert; ein eigenes Label traegt die menschenlesbare Bezeichnung fuer Reports,
   Verzeichnisnamen und Meldungen. Bereits bestehende, aus Slugs entstandene Ids bleiben als
   opake Werte gueltig -- sie werden nicht umbenannt, sondern erhalten ihre bisherigen
   Verzeichnispfade als Anker.

5. **Die Registry lebt in einem reservierten System-Dataset desselben Stores** und wird ueber
   denselben Schreibweg wie alle Modelldaten geschrieben (SHACL-Write-Gate, WriteFunnel,
   Head-als-Concurrency-Token). Nebenlaeufige Anlage desselben Ankers aus parallelen Sessions
   kollidiert dadurch sauber, statt sich zu ueberschreiben.

6. **Jeder Workspace beschreibt sich zusaetzlich in seinem eigenen Dataset selbst** (Anker,
   Label). Die Registry ist damit ein aus den Datasets wiederherstellbarer Index und kein
   Single Point of Failure; ein aus einem Backup zurueckgespieltes Dataset traegt seine
   Identitaet mit sich.

7. **Der Workspace-Lebenszyklus wird ein eigener Bounded Context** (`arknet-workspace`) mit
   eigenen Tools zum Anlegen, Anhaengen weiterer Anker, Auflisten und Umbenennen. Er ist die
   Selbstbedienungsflaeche fuer den treibenden Agenten und zugleich die Quelle der
   Workspace-Liste fuer Oberflaechen, die kein Herkunftsverzeichnis kennen.

8. **Die abgeleitete Aufloesung entfaellt ersatzlos.** Slug-Bildung, git-Ableitung, das explizite
   Server-Property fuer eine gepinnte Id, der Rueckfall auf das Server-Arbeitsverzeichnis und der
   Default-Workspace werden entfernt, nicht als Fallback behalten. Ein zweiter, stillschweigend
   greifender Aufloesungspfad wuerde genau die Klasse Fehler zurueckbringen, die diese Entscheidung
   beseitigt.

## Konsequenzen

**Positiv:** Kollision ist strukturell ausgeschlossen, weil der Anker der Schluessel ist und
nichts mehr verkuerzt wird. Fragmentierung ebenso: ein weiteres Verzeichnis desselben Modells
wird angehaengt statt geraten. git-Projekte und nicht-git-Projekte sind gleichgestellt, weil
keine Ableitung mehr existiert, die das eine bevorzugt. Der Server braucht keinerlei Sicht auf
das Projekt und funktioniert lokal wie im Netz unveraendert. Die Zuordnung ist wieder umkehrbar:
zu jedem Workspace sind seine Anker abfragbar, womit ein Export seine Identitaet eindeutig
mitfuehrt und ein Restore sie wiederfindet. Clients ohne besondere Faehigkeiten bleiben
bedienbar -- ein statischer Header genuegt, jede darueber hinausgehende clientseitige
Vorverarbeitung ist Komfort, nicht Voraussetzung.

**Negativ / bewusst deferred (YAGNI):** Isolation ist nicht mehr zero-config. Jeder Workspace
braucht einen einmaligen Anlage- oder Anhaenge-Schritt, und ein Client, der heute ohne Anker
schreibt, scheitert danach sichtbar -- gewollt, aber es ist eine echte Bruchstelle im Betrieb.
Der Anker bleibt Routing und ist keine Authentifizierung: eine kopierte Client-Konfiguration
traegt denselben Anker an einen zweiten Ort, was sich anhand des zuletzt gesehenen Ankers
erkennen, aber an dieser Vertrauensgrenze nicht verhindern laesst (ADR-009 Punkt 4 gilt
unveraendert). Wird der Anker als Tool-Parameter uebergeben, stammt er vom Sprachmodell; ein
frei geratener, aber zufaellig existierender Anker trifft still den falschen Workspace --
deshalb bleibt der Header der Primaerweg und lange, wenig ratbare Anker sind vorzuziehen.
Die Registry ist Zustand im Aufrufpfad: jede Aufloesung schlaegt in ihr nach, und ihr Dataset
muss aus dem generischen Lesepfad ausgeblendet werden, wie es der Provenance-Graph bereits ist.
Ein eigener Bounded Context fuer den Workspace-Lebenszyklus ist mehr Flaeche als ein Resolver;
das ist der Preis dafuer, dass Identitaet ein verwalteter Gegenstand mit eigenen Invarianten
wird statt einer Funktion ueber einen Verzeichnisnamen. Autorisierung ist hier bewusst nicht
mitentschieden: wer was in welchem Workspace darf, ist eine Frage nach Principals und gehoert
nicht in die Ressourcen-Identitaet -- ein spaeteres Berechtigungsmodell referenziert die
WorkspaceId, statt mit ihr verschmolzen zu werden.

## Alternativen

- **Geslugter Basename beibehalten (Status quo).** Verwirft den Pfad und laesst gleichnamige
  Projekte im selben Store landen. Verworfen -- die Fehlerklasse ist der Anlass dieser
  Entscheidung.
- **Vollen Pfad ableiten (Slug oder Hash des absoluten Pfades).** Beseitigt die Kollision ohne
  neuen Zustand, macht dafuer jedes Unterverzeichnis zu einem eigenen, leeren Workspace und
  verliert den Store beim Verschieben des Projekts. Verworfen (tauscht stille Vermischung gegen
  stille Zersplitterung).
- **git-common-dir als Anker, notfalls clientseitig vorverdaut.** Setzt git voraus, schliesst
  nicht-git-Projekte aus und verlangt vom Server Sicht auf das Projekt oder vom Client ein
  ausgefuehrtes Hilfsskript. Als optionale Vorverarbeitung des Ankers weiterhin moeglich, als
  Fundament der Identitaet verworfen.
- **MCP `roots/list` als Identitaetsquelle.** Der protokolleigene Weg, Verzeichnisse vom Client
  zu erfahren, und als zusaetzlicher Lieferweg des Ankers zulaessig. Liefert aber nur das
  Startverzeichnis der Client-Sitzung -- dieselbe unzuverlaessige Information, die schon der
  Header traegt -- und setzt eine Client-Capability voraus. Als Identitaetsquelle verworfen.
- **Praefix-Match ueber registrierte Pfade.** Wuerde Unterverzeichnisse automatisch zuordnen,
  ist aber reihenfolgeabhaengig (wer zuerst kommt, definiert die Wurzel) und fuehrt eigenstaendige
  Unterprojekte faelschlich zusammen. Verworfen zugunsten explizit angehaengter Anker.
- **Registry als Datei im Store-Root.** Schnell gebaut und von Hand reparierbar, verlangt aber
  Atomaritaet, Nebenlaeufigkeitsschutz und Schemapruefung als zweiten Persistenzweg neben dem
  bereits vorhandenen. Verworfen.
- **Nur Selbstbeschreibung im jeweiligen Dataset, ohne zentralen Index.** Jede Aufloesung
  muesste alle Datasets oeffnen, und die Eindeutigkeit eines Ankers waere nicht konfliktfrei
  pruefbar. Als alleiniger Ort verworfen, als Redundanz uebernommen.
- **Statische, vom Betreiber gepflegte Zuordnungskonfiguration.** Kein Selbstbedienungspfad fuer
  den treibenden Agenten, jede Aenderung erfordert einen Eingriff am Serverbetrieb. Verworfen.
- **Modulname `arknet-identity` mit Blick auf spaetere Autorisierung.** Vermischt zwei
  Gegenstaende mit verschiedenen Lebenszyklen -- welches Modell ist gemeint (Routing) und wer
  darf darauf zugreifen (Principal). Verworfen; das Modul heisst nach dem, was es verwaltet.
