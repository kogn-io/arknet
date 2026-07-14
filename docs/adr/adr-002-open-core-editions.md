# ADR-002: Open-Core-Editions-Modell

- Status: Proposed (2026-07-14) -- wird Accepted, sobald die OSS-Lizenz festgelegt ist
- Verwandt: ADR-001, ADR-003, ADR-004

## Kontext

ADR-001 schneidet Persistenz hinter einen domaennahen Out-Port mit zwei Adaptern:
Adapter A (lokal, kognio-rdf) und Adapter B (remote, kognio-memory). Diese Port-Grenze ist
zugleich eine natuerliche Produkt- und Monetarisierungsgrenze: Der lokale Single-User-Weg
kann offen liegen, der Team-/Remote-Weg proprietaer bleiben. Offen ist, wie arknet in
Distributionen zerlegt und lizenziert wird.

## Entscheidung

arknet folgt einem **Open-Core-Modell** entlang der Adapter-Grenze aus ADR-001:

- **Community Edition = OSS:** Komponenten-Cores + Adapter A + Spring-AI-MCP-Layer. Dient
  als Spring-AI-+-RDF-Showcase und Community-Building. Konkrete OSS-Lizenz noch offen.
- **Closed Edition = proprietaer:** Adapter B (remote Store, ADR-003) + Team-Distribution.

Die Community-Distribution enthaelt nur Adapter A; die Composition Root waehlt den Adapter
zur Build-/Distributionszeit. Adapter B lebt in einem separaten, nicht-ausgelieferten
Modul/Repo.

## Konsequenzen

**Positiv:** Klare Editions-/Monetarisierungsgrenze entlang der Port-Grenze -- kein
Sonderschnitt noetig, die Hexagonal-Struktur traegt sie schon. Die OSS-Edition ist ein
vollstaendig nutzbarer lokaler Client, kein Demo-Torso.

**Negativ / offen:** Die OSS-Lizenz der Community Edition ist noch nicht gewaehlt -- bis
dahin ist die CE nicht formal lizenzierbar. Eine zweite Distribution/Repo fuer die Closed
Edition erzeugt Pflegeaufwand (Build, Release-Kanal); erst relevant, wenn Adapter B gebaut
wird (ADR-003).

## Alternativen

- **Alles OSS, auch Adapter B.** Verschenkt die einzige natuerliche Monetarisierungsgrenze,
  ohne dass der lokale Client dadurch besser wird. Vorerst verworfen (revidierbar).
- **Alles proprietaer.** Kein Community-/Showcase-Effekt; widerspricht der MCP-first-
  Positionierung als offenes Substrat. Verworfen.
