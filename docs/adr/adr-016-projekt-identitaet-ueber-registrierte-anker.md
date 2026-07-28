# ADR-016: Projekt-Identitaet ueber registrierte Anker statt abgeleiteter Namen

- Status: Proposed (2026-07-27)
- Verwandt: ADR-001 (loest die dort festgehaltene Herkunft der Store-Identitaet ab -- Aufloesung
  beim Serverstart ueber eine Slug-Kette), ADR-009 (loest dessen Punkt 3 ab -- Ableitung aus dem
  Herkunftsverzeichnis per git-common-dir; Transport, Betriebsmodell und Vertrauensgrenze bleiben
  unveraendert gueltig), ADR-006 (generischer Lesepfad, der das Registry-Dataset ausblenden muss)

## Kontext

Die Identitaet des Stores, den ein Aufruf trifft, wurde bisher aus dem Herkunftsverzeichnis des
Clients *abgeleitet*: geslugter Basename des git-Ankers, sonst des Arbeitsverzeichnisses. Eine
Ableitung ist nur so gut wie die Information, die der Server hat -- und er hat keine. Er erhaelt
eine Zeichenkette und kann sie weder pruefen noch anreichern. Der Basename wirft den Pfad weg,
also fallen zwei gleichnamige Verzeichnisse an verschiedenen Orten auf dieselbe Id, dasselbe Dataset
und dasselbe Store-Verzeichnis: stille Vermischung zweier Architekturmodelle, ohne Fehlermeldung
und ohne erkennbares Symptom, bis Business-Codes und Cross-Referenzen projektuebergreifend
kollidieren.

Der git-Anteil der Ableitung traegt zudem nicht, wo er gebraucht wird. Er setzt voraus, dass der
Serverprozess das Projektverzeichnis sieht und `git` ausfuehren kann. Der ausgelieferte Daemon
laeuft containerisiert, sieht keine Projektverzeichnisse und enthaelt kein `git`; die Ableitung
faellt dort ausnahmslos auf den Basename zurueck. Fuer nicht-git-Projekte existierte der
git-Zweig ohnehin nie. Er war eine Sonderfallregel fuer eine Teilmenge, kein Fundament. arknet
ist ausserdem ausschliesslich eine Server-Anwendung -- lokal betrieben oder spaeter im Netz --
und nie ein im Projektverzeichnis mitlaufender Prozess. Ein Betriebsmodus, in dem der Server das
Verzeichnis selbst inspizieren koennte, ist damit nicht bloss unpraktisch, sondern nicht
vorgesehen.

Damit steht die Frage nicht mehr, *wie* aus einem Verzeichnis eine Id abgeleitet wird, sondern
ob abgeleitet werden darf.

Die zweite, laenger unklare Frage ist, *worauf* die Id ueberhaupt zeigt. Sie hiess bisher
"Workspace" -- ein Begriff aus der Arbeitsweise in der IDE, wo jedes Verzeichnis ein Workspace
ist. Er hat nie einen modellierten Gegenstand bezeichnet, sondern zufaellig beschrieben, was der
Client sendet. Der Gegenstand, an dem Requirements, Glossarbegriffe und Use Cases tatsaechlich
haengen, ist das **Projekt**. "Projekt" bezeichnet von hier an durchgehend diesen
Modellgegenstand -- nicht das Verzeichnis, aus dem ein Client arbeitet; das heisst weiterhin
Arbeits- oder Projektverzeichnis. Ein zusaetzlicher Workspace-Begriff *ueber* dem Projekt haette
bedeutet, dass ein Dataset die Daten mehrerer Projekte haelt und die Trennung innerhalb des
Datasets nachgezogen werden muss -- jeder Lese- und Schreibpfad in allen vier Bounded Contexts
braeuchte einen Projektfilter, und die Eindeutigkeit der Business-Codes haenge an dessen
korrekter Anwendung statt an der Store-Grenze. Die fachlichen Kerne duerfen von nichts anderem
abhaengen als vom Projekt.

## Entscheidung

Projekt-Identitaet wird **registriert, nicht abgeleitet**. Der Store-Gegenstand ist das Projekt.

1. **Ein Dataset haelt die Daten genau eines Projekts.** Es gibt keine Ebene zwischen Projekt und
   Store. Der Begriff "Workspace" entfaellt ersatzlos -- als Store-Gegenstand, als
   Modellierungsebene und als Name im Code (`WorkspaceId` wird `ProjectId`, der
   `WorkspaceResolver`-Port entsprechend). Damit bleiben Business-Code-Vergabe und
   Cross-Referenzen genau dort eindeutig, wo sie es heute sind: an der Store-Grenze.

2. **Der Client sendet einen Anker, der Server interpretiert ihn nicht.** Ein Anker ist eine
   opake Zeichenkette mit einem Typ (`path`, `url`, `uuid`, spaeter weitere). Ob er wie ein
   Dateipfad aussieht, ist Sache des Clients; der Server schlaegt ihn ausschliesslich nach.
   Uebertragen wird er per HTTP-Header oder -- fuer Clients ohne Header-Kontrolle -- als
   expliziter Tool-Parameter. Beide Wege stehen jedem MCP-Client offen und setzen weder eine
   Client-Capability noch git voraus.

   Anker und ProjectId sind bewusst zwei Dinge: der Anker ist der Haltepunkt, an dem sich ein
   Client festmacht und den er kennt, weil er dort arbeitet; die ProjectId ist die opake
   Identitaet, die der Server fuehrt. Diese Trennung ist der Grund, warum mehrere Anker auf
   dasselbe Projekt zeigen koennen, ohne dass es das Projekt doppelt gibt.

3. **Unbekannter Anker ist ein Fehler, kein Default.** Ein Aufruf mit unbekanntem oder ohne
   Anker scheitert mit einer Meldung, die auf die Anlage eines Projekts verweist. Es gibt
   keinen impliziten Default und keinen Rueckfall auf ein Server-Arbeitsverzeichnis: ein
   Schreibvorgang ohne geklaerte Zugehoerigkeit ist genau der Fehler, den diese Entscheidung
   ausschliesst.

4. **Ein Projekt haelt mehrere Anker; ein Anker gehoert zu genau einem Projekt.** Das deckt die
   Faelle, in denen dasselbe Projekt aus mehreren Verzeichnissen bearbeitet wird -- git-Worktrees
   neben dem Hauptcheckout, mehrere IDE-Verzeichnisse desselben Projekts, eine Kopie an anderem
   Ort. Die Eindeutigkeit des Ankers ueber alle Projekte hinweg ist die zentrale Invariante des
   Modells. Diese N:1-Beziehung ersetzt den git-common-dir-Griff aus #136: was dort abgeleitet
   werden sollte, wird jetzt angehaengt.

5. **Identitaet und Beschriftung sind getrennt.** Die ProjectId ist opak und wird nie
   interpretiert; ein eigenes Label traegt die menschenlesbare Bezeichnung fuer Reports,
   Verzeichnisnamen und Meldungen. Bereits bestehende, aus Slugs entstandene Ids bleiben als
   opake Werte gueltig -- sie werden nicht umbenannt, sondern erhalten ihre bisherigen
   Verzeichnispfade als Anker.

6. **Die Registry lebt in einem reservierten System-Dataset desselben Stores** und wird ueber
   denselben Schreibweg wie alle Modelldaten geschrieben (SHACL-Write-Gate, WriteFunnel,
   Head-als-Concurrency-Token). Nebenlaeufige Anlage desselben Ankers aus parallelen Sessions
   kollidiert dadurch sauber, statt sich zu ueberschreiben.

7. **Jedes Projekt beschreibt sich zusaetzlich in seinem eigenen Dataset selbst** (Anker,
   Label). Die Registry ist damit ein aus den Datasets wiederherstellbarer Index und kein
   Single Point of Failure; ein aus einem Backup zurueckgespieltes Dataset traegt seine
   Identitaet mit sich.

8. **Der Projekt-Lebenszyklus wird ein eigener Bounded Context** (`arknet-project`) mit eigenen
   Tools zum Anlegen, Anhaengen weiterer Anker, Auflisten und Umbenennen. Er ist die
   Selbstbedienungsflaeche fuer den treibenden Agenten und zugleich die Quelle der Projektliste
   fuer Oberflaechen, die kein Herkunftsverzeichnis kennen.

9. **Die abgeleitete Aufloesung entfaellt ersatzlos.** Slug-Bildung, git-Ableitung, das explizite
   Server-Property fuer eine gepinnte Id, der Rueckfall auf das Server-Arbeitsverzeichnis und der
   Default werden entfernt, nicht als Fallback behalten. Ein zweiter, stillschweigend greifender
   Aufloesungspfad wuerde genau die Klasse Fehler zurueckbringen, die diese Entscheidung
   beseitigt.

## Konsequenzen

**Positiv:** Kollision ist strukturell ausgeschlossen, weil der Anker der Schluessel ist und
nichts mehr verkuerzt wird. Fragmentierung ebenso: ein weiteres Verzeichnis desselben Projekts
wird angehaengt statt geraten. git-Projekte und nicht-git-Projekte sind gleichgestellt, weil
keine Ableitung mehr existiert, die das eine bevorzugt. Der Server braucht keinerlei Zugriff auf
das Arbeitsverzeichnis des Clients und funktioniert lokal wie im Netz unveraendert. Die Zuordnung
ist wieder umkehrbar:
zu jedem Projekt sind seine Anker abfragbar, womit ein Export seine Identitaet eindeutig
mitfuehrt und ein Restore sie wiederfindet. Die fachlichen Kerne bleiben unberuehrt -- weil
Dataset und Projekt zusammenfallen, braucht kein Lese- oder Schreibpfad einen Projektfilter, und
die Business-Code-Vergabe behaelt ihre heutige Eindeutigkeitsgrenze. Clients ohne besondere
Faehigkeiten bleiben bedienbar: ein statischer Header genuegt.

**Negativ / bewusst deferred (YAGNI):** Isolation ist nicht mehr zero-config. Jedes Projekt
braucht einen einmaligen Anlage- oder Anhaenge-Schritt, und ein Client, der heute ohne Anker
schreibt, scheitert danach sichtbar -- gewollt, aber es ist eine echte Bruchstelle im Betrieb.
Ein Modell, das mehrere Repositories unter einem gemeinsamen Glossar umspannt, ist damit nicht
abgebildet: entweder sind es ein Projekt mit mehreren Ankern, oder es sind getrennte Projekte
mit getrennten Glossaren. Eine Klammer darueber ist bewusst nicht gebaut (siehe Alternativen).
Der Anker bleibt Routing und ist keine Authentifizierung: eine kopierte Client-Konfiguration
traegt denselben Anker an einen zweiten Ort, was sich anhand des zuletzt gesehenen Ankers
erkennen, aber an dieser Vertrauensgrenze nicht verhindern laesst (ADR-009 Punkt 4 gilt
unveraendert). Wird der Anker als Tool-Parameter uebergeben, stammt er vom Sprachmodell; ein
frei geratener, aber zufaellig existierender Anker trifft still das falsche Projekt -- deshalb
bleibt der Header der Primaerweg und lange, wenig ratbare Anker sind vorzuziehen. Die Registry
ist Zustand im Aufrufpfad: jede Aufloesung schlaegt in ihr nach, und ihr Dataset muss aus dem
generischen Lesepfad ausgeblendet werden, wie es der Provenance-Graph bereits ist. Ein eigener
Bounded Context fuer den Projekt-Lebenszyklus ist mehr Flaeche als ein Resolver; das ist der
Preis dafuer, dass Identitaet ein verwalteter Gegenstand mit eigenen Invarianten wird statt
einer Funktion ueber einen Verzeichnisnamen. Die Umbenennung `WorkspaceId` -> `ProjectId` zieht
durch Shared Kernel, Resolver-Port, Header-Namen und Doku. Autorisierung ist hier bewusst nicht
mitentschieden: wer was in welchem Projekt darf, ist eine Frage nach Principals und gehoert
nicht in die Ressourcen-Identitaet -- ein spaeteres Berechtigungsmodell referenziert die
ProjectId, statt mit ihr verschmolzen zu werden.

## Alternativen

- **Geslugter Basename beibehalten (Status quo).** Verwirft den Pfad und laesst gleichnamige
  Projekte im selben Store landen. Verworfen -- die Fehlerklasse ist der Anlass dieser
  Entscheidung.
- **Vollen Pfad ableiten (Slug oder Hash des absoluten Pfades).** Beseitigt die Kollision ohne
  neuen Zustand, macht dafuer jedes Unterverzeichnis zu einem eigenen, leeren Projekt und
  verliert den Store beim Verschieben des Verzeichnisses. Verworfen (tauscht stille Vermischung gegen
  stille Zersplitterung).
- **Workspace als Klammer ueber mehreren Projekten, ein Dataset je Workspace.** Haette erlaubt,
  mehrere Repositories unter einem gemeinsamen Glossar zu fuehren. Verworfen: der Client sendet
  pro Aufruf genau einen Anker, und zeigt dieser auf die Klammer, ist die Projektzugehoerigkeit
  der geschriebenen Daten verloren -- die Requirements aller Projekte laegen ununterscheidbar in
  einem Dataset. Rettbar waere das nur, indem jede Ressource ihre Projektzugehoerigkeit traegt
  und jeder Lese- und Schreibpfad in allen vier Bounded Contexts danach filtert. Genau diese
  Abhaengigkeit der fachlichen Kerne von einer Ebene oberhalb des Projekts ist nicht gewollt.
- **Anker zeigt auf das Projekt, Workspace nur als Sicht-/Gruppierungsebene darueber.** Vermeidet
  das Filterproblem, weil die Store-Grenze am Projekt bleibt, fuegt aber eine Ebene hinzu, die
  ausser einer Gruppierung in Oberflaechen nichts leistet. Verworfen, bis ein konkreter Bedarf
  auftritt -- sie liesse sich spaeter additiv ergaenzen, ohne die Store-Grenze anzufassen.
- **git-common-dir als Anker, notfalls clientseitig vorverdaut.** Setzt git voraus, schliesst
  nicht-git-Projekte aus und verlangt vom Server Zugriff auf das Projektverzeichnis oder vom
  Client ein ausgefuehrtes Hilfsskript. Als optionale Vorverarbeitung des Ankers weiterhin moeglich, als
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
