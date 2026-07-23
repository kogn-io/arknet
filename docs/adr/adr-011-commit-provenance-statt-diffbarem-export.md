# ADR-011: Traceability ueber Commit-Provenance statt diffbarem Datei-Export

- Status: Proposed (2026-07-23)
- Verwandt: ADR-001, ADR-005

## Kontext

ADR-005 hat den Store zum primaeren Ort des Modells gemacht und die Datei-Pipeline
entfernt; ein Import-/Export-Pfad existiert seither nicht mehr. Damit blieb offen, wie
Aenderungen am Modell nachvollziehbar werden und wie das Modell mit dem Code
zusammenhaengt, an dem es haengt.

Die naheliegende Antwort war ein **deterministisch sortierter, diffbarer Datei-Export**
(Turtle/TriG) im Repo des Nutzers, gegen den Pull Requests laufen. Sie traf die
Erwartung der ersten Zielgruppe -- KI-gestuetzte Entwickler, die Wissen im Repo haben
wollen -- und musste gegen vier Kraefte geprueft werden:

1. **Merge-Modell.** Git merged Zeilen, RDF merged Tripel. Ein Drei-Wege-Textmerge auf
   Turtle/TriG kann syntaktisch gueltig und semantisch falsch sein.
2. **Was der Store traegt und Git nicht.** Transaktionen, das SHACL-Write-Gate (ADR-007)
   und feingranulare Rechte. Als Speicher muesste Git sie dauerhaft nachbauen.
3. **Werkzeuglage.** Das eingesetzte RDF-Substrat bietet weder eine Kanonisierung noch
   eine Sortier-Option beim Schreiben; ein diffbarer Export waere vollstaendig Eigenbau,
   und seine Stabilitaet waere eine dauerhafte Regressionsflaeche.
4. **Zielgruppen-Reichweite.** Spaetere Nutzer in regulierten Kontexten arbeiten nicht
   ueber Pull Requests. Ein Entwurf, der Git zur Bedingung macht, verbaut sie.

Dabei zeigte sich, dass der eigentliche Nutzen nicht in der Dateihaltung liegt: gefragt
ist nicht "meine Requirements sind Dateien", sondern "dieser Commit gehoert zu diesem
Requirement". Das ist Traceability, und dafuer braucht es keinen Datei-Export.

ADR-001 hat Provenance bewusst nach hinten gestellt ("generatedBy = Agent additiv
spaeter, PROV-konform"). Dieses ADR loest sie ein und legt ihre Form fest.

## Entscheidung

1. **Es wird kein diffbarer Datei-Export gebaut.** Der Store bleibt der primaere Ort des
   Modells (ADR-005 unveraendert). Datei-Ausgabe existiert nur als nicht-diffbarer
   Volldump zu Backup- und Portabilitaetszwecken -- nicht sortiert, nicht als
   Merge-Grundlage gedacht.

2. **Jede Modellaenderung erzeugt eine interne, immutable Revision.** Sie ist der Traeger
   der PROV-O-Kette. Das gilt fuer **jeden** Schreibpfad ohne Ausnahme -- Agent ueber MCP,
   Import, kuenftige UI. Eine Ausnahme erzeugte eine stille Luecke im Nachweis.

3. **Ein Git-Commit ist eine eigenstaendige Entitaet im Modell, verknuepft mit Revisionen
   ueber eine n:m-Relation** -- nicht als optionales Attribut an der Revision. Eine
   Aenderung zieht typischerweise mehrere Commits nach sich, ein Commit kann mehrere
   Revisionen betreffen.

4. **Die Verknuepfung wird als Commit-Message-Trailer gespeichert:** ein Schluessel
   `Arknet:`, wiederholbar, Wert ist ein arknet-Code beliebigen Typs.

   ```
   Arknet: REQ-42
   Arknet: UC-7
   ```

   Der Trailer ist die **einzige** Quelle des Links. Der Agent schreibt ihn beim Commit;
   arknet liest ihn. Es gibt keinen zweiten Meldeweg.

5. **Commits werden aus der Git-Historie gelesen, nicht ueber einen Hook erfasst.**
   Ein Hook eines bestimmten Agenten-Werkzeugs deckt nur dessen eigene Commits ab.

6. **Git ist ein optionaler Connector, keine Voraussetzung.** Ein Projekt ohne Repository
   ist voll funktionsfaehig. Fuer Commit-Metadaten genuegt lesender Zugriff auf Log bzw.
   Forge-API; eine serverseitige Arbeitskopie ist dafuer nicht noetig.

7. **Fehlende und ungueltige Verknuepfungen werden sichtbar gemacht**, nicht
   weggelassen: ein Commit ohne Trailer erscheint als unverknuepft, ein Trailer auf einen
   unbekannten Code als baumelnde Referenz.

## Konsequenzen

**Positiv:**

- Traceability ohne Datei-Merge-Semantik: die Nichtuebereinstimmung zwischen Git und RDF
  wird umgangen statt nachgebaut. Sortierung, Datei-Layout und Merge-Strategien entfallen
  ersatzlos als Problemklasse.
- Die Erfassung ist werkzeugunabhaengig und wirkt rueckwirkend: sie funktioniert fuer
  Commits beliebiger Autoren und auf bestehenden Repositories, unabhaengig davon, welches
  Werkzeug den Commit erzeugt hat.
- Der Link ueberlebt im Repository und damit einen vollstaendigen Neuaufbau des Stores.
- Ein Schreibpfad und ein Schiedsrichter: der Store validiert, das Repository liefert
  Belege. Es entsteht keine Versoehnungslogik zwischen konkurrierenden Quellen.
- Spaetere Zielgruppen bleiben erreichbar: wer ueber eine Oberflaeche arbeitet, sieht
  verknuepfte Commits als Belege und muss von Git nie erfahren.
- Die Revision traegt zugleich die Grundlage fuer eine Aenderungsansicht (Revision gegen
  Revision) -- ohne sie gaebe es nichts zu vergleichen.

**Negativ / bewusst deferred (YAGNI):**

- **Speicherwachstum.** Jede Aenderung dupliziert die betroffene Ressource. Eine
  Kompaktierungs- oder Archivierungsstrategie bleibt bewusst offen, bis die reale
  Wachstumsrate bekannt ist -- sie vorab zu entwerfen hiesse raten.
- **Der Trailer haengt am Autor des Commits.** Fuer Agenten-Commits ist er eine
  Werkzeug-Instruktion, fuer manuelle Commits Konvention. Nicht getaggte Commits bleiben
  unverknuepft. Das wird bewusst in Kauf genommen, statt Links zu erraten: eine falsche
  Verknuepfung ist schaedlicher als eine fehlende, weil sie einen Nachweis vortaeuscht.
- **Verdichtende Merge-Strategien koennen Trailer verstuemmeln**, wenn die Forge mehrere
  Commit-Nachrichten zusammenfuehrt. Ergebnis ist ein unverknuepfter Commit -- sichtbar,
  aber verloren.
- **Kein Review des Modells ueber Pull Requests.** Modellaenderungen werden nicht im
  Git-Workflow begutachtet; ein Freigabe- und Review-Weg muss ueber eine eigene
  Oberflaeche entstehen.
- **Portabilitaet nur ueber den Volldump.** Wer seine Daten mitnehmen will, bekommt einen
  vollstaendigen, aber nicht diffbaren Stand -- kein zeilenweise nachvollziehbares
  Aenderungsprotokoll ausserhalb des Stores.
- **Feingranulare Freigabe- und Zugriffsrechte auf Modellebene bleiben offen.** Sie sind
  fuer regulierte Nutzung noetig, aber weder von dieser Entscheidung geloest noch von ihr
  verbaut.

## Alternativen

- **Deterministisch sortierter, diffbarer Datei-Export mit Pull Requests dagegen.**
  Verworfen: Git merged Zeilen, RDF merged Tripel -- ein Textmerge kann semantisch falsche
  Graphen erzeugen. Dazu vollstaendiger Eigenbau von Kanonisierung und Sortierung sowie
  dauerhafter Nachbau von Transaktionen, SHACL-Gate und Rechten.
- **Git als Persistenz (Source of Truth), Store nur als abgeleitete Projektion.**
  Verworfen: dieselbe Merge-Nichtuebereinstimmung, zusaetzlich Verlust von Transaktionen
  und feingranularen Rechten. Jeder Schreibvorgang wuerde zum Commit.
- **Eine Datei je Named Graph als Repo-Layout.** Verworfen: das Datei-Layout haengt damit
  an Graph-Namen, deren Stabilitaet nicht zugesichert werden kann; jede Umbenennung waere
  eine Reorganisation des Repositories.
- **Erfassung ueber einen Hook des Agenten-Werkzeugs statt Lesen der Historie.**
  Verworfen: deckt nur Commits dieses einen Werkzeugs ab. Commits aus IDE, Kommandozeile,
  CI oder nach einem Rebase fehlten -- stille Luecken genau dort, wo Vollstaendigkeit
  behauptet wird.
- **Eigenes Werkzeug, mit dem der Agent den Link direkt meldet, statt eines Trailers.**
  Verworfen: zweite Quelle neben dem Repository, mit Widerspruchspotenzial und
  Versoehnungslogik; der Link ueberlebte weder einen Store-Neuaufbau noch die Weitergabe
  des Repositories.
- **Getypte Trailer-Schluessel (`Req:` / `UC:` / `ADR:`).** Verworfen: das Code-Praefix
  traegt den Typ bereits; ein einziger Schluessel bleibt bei neuen Ressourcentypen
  unveraendert und erspart eine Schluesselliste, die mitwachsen muesste.
- **Verknuepfung ueber Zeitfenster oder Sitzungskorrelation.** Verworfen: raet unter
  Nebenlaeufigkeit, und ADR-001 haelt Mehrfach-Sessions gegen denselben Store
  ausdruecklich fuer den Normalfall.
- **Verknuepfung ueber eine Branch-Namens-Konvention.** Verworfen: Branches werden nach
  dem Merge geloescht und ueberleben verdichtende Merges nicht -- rueckwirkend nicht
  auswertbar.
