# ADR-017: ISO/IEC/IEEE 15288 als Scope-Orientierung, kein Implementierungsziel

- Status: Proposed (2026-08-02) -- wird Accepted, sobald der Rahmen an einer konkreten
  Scope-Entscheidung angewendet und bestaetigt wurde

## Kontext

arknet schneidet den Ontologie-Scope bisher ad-hoc entlang von DDD/arc42 -- bei jeder
Erweiterung neu, ohne benannten Massstab. Bereits skizzierte Module (Privacy/DSGVO,
Prozess/State-Machine, taktisches DDD, volle ISO-42010-Apparatur) lassen sich mit
"DDD+arc42" allein nicht erklaeren.

## Entscheidung

arknet orientiert den Ontologie-Scope an ISO/IEC/IEEE 15288s Technical-Process-Gruppe
(Stakeholder Needs/Requirements Definition, Architecture Definition, Design Definition)
zusammen mit Decision Management und Configuration Management. Diese Gruppen benennen die
Grenze, die im bestehenden Modul-Schnitt schon implizit steckt. Agreement Processes,
Verification/Validation, Transition/Operation/Maintenance/Disposal sowie
Organizational-Project-Enabling-Prozesse bleiben explizit ausserhalb -- arknet ist ein
Design-Time-Dokumentationswerkzeug, kein Betriebs-, Test- oder Beschaffungswerkzeug.
Referenzrahmen fuer Scope-Entscheidungen, kein Konformitaetsziel und kein Ontologie-Import.

## Konsequenzen

**Positiv:** Ein benennbares Kriterium fuer kuenftige Scope-Fragen statt Einzelfallabwaegung.
Erklaert bereits existierende geparkte Module (Privacy, Prozess, taktisches DDD,
ISO-42010-Apparatur) als kohaerente Teilmenge statt Ad-hoc-Wachstum.

**Negativ / bewusst deferred (YAGNI):** Ein zweiter Referenzrahmen neben DDD/arc42/OSLC-RM,
der mit diesen konsistent gehalten werden muss. Risiko, dass die ausgeschlossenen
Prozessgruppen spaeter stueckweise hereinrutschen, ohne dass dafuer eine neue Entscheidung
getroffen wird.

## Alternativen

- **Kein benannter Rahmen, weiter ad-hoc.** Verworfen -- genau das aktuelle Problem.
- **Volle ISO/IEC/IEEE-15288-Konformitaet (alle Prozessgruppen).** Verworfen -- zu gross fuer
  ein Design-Time-Werkzeug eines einzelnen Autors; verlangt Agreement- und
  Betriebsapparatur, fuer die arknet keine Verwendung hat.
- **Nur ISO/IEC/IEEE 42010 (Architekturbeschreibung).** Verworfen -- zu eng, deckt
  Requirements Definition sowie Decision/Configuration Management nicht ab, die im Store
  bereits tragende Bestandteile sind.
