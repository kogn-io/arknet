# ADR-020: Constraint-Text ist korrigierbar, Klassifikation und Code nicht

- Status: Accepted (2026-08-07)
- Related: ADR-014, ADR-015

## Kontext

Constraint kam als zweiter Ressourcentyp der Requirements-BC hinzu -- nicht als eigener Bounded
Context, sondern als Nachbar des Requirements im selben Hexagon. Er war von Anfang an nach der
Anlage unveraenderlich. Diese Unveraenderlichkeit war allerdings nie eine minutierte Entscheidung,
sondern eine Konvention im Code: begruendet wurde ausschliesslich das Fehlen eines
Status-Wechsels, und zwar zutreffend mit der Ontologie, die fuer Constraint kein Status-Feld
kennt. Fuer das Fehlen eines *Korrektur*-Wegs stand nirgends eine Begruendung -- die Abwesenheit
war da, das Argument dafuer nicht.

Dagegen steht der Mehrsprachigkeits-Mechanismus des Stores. Die zentralen benennenden und
beschreibenden Felder tragen je Sprache ein eigenes, sprachgetaggtes Literal, und ein Schreibaufruf
traegt dabei genau einen Sprachtag. Aus diesen beiden Eigenschaften folgt zwingend: eine Ressource,
die nur ein einziges Mal geschrieben werden kann, bleibt zwangslaeufig einsprachig. Zweisprachigkeit
entsteht ueberall sonst in der BC ueber zwei Aufrufe -- Anlage in der einen Sprache, Korrektur in
der zweiten.

Damit kollidierte die Unveraenderlichkeit direkt mit der Mehrsprachigkeit. Constraint war der
einzige Ressourcentyp der BC, dessen Freitextfelder nicht mehrsprachig sein konnten, und die
Ursache war nicht ein fehlendes Argument an der Tool-Oberflaeche, sondern der fehlende zweite
Schreibvorgang. Die Validierungsschicht verbot ein sprachgetaggtes Literal fuer den
Constraint-Text zusaetzlich aktiv, indem sie den Datentyp auf eine ungetaggte Zeichenkette
festlegte. Beide Punkte waren nicht unabhaengig voneinander loesbar: ein aufgeweichtes Shape ohne
zweiten Schreibweg haette die Mehrsprachigkeit erlaubt, aber unerreichbar gelassen.

Auf der anderen Seite steht die Rolle des Business-Codes. Requirements binden Constraints ueber
eine gerichtete Kante, und der Code ist dabei nicht nur ein Anzeigelabel: er ist der Name, unter
dem Menschen und Prosa den Constraint ausserhalb des Modells referenzieren. Die Klassifikation
eines Constraints -- technisch, geschaeftlich oder regulatorisch -- bestimmt zugleich das
Praefix dieses Codes und damit den Zaehler, aus dem er stammt. Klassifikation und Code sind
deshalb nicht zwei Felder, sondern zwei Sichten auf dieselbe Festlegung.

## Entscheidung

Die Aenderbarkeitsgrenze eines Constraints verlaeuft zwischen seinem Text und seiner Identitaet.

1. Titel und Statement sind nachtraeglich korrigierbar und tragen je Sprache ein eigenes Literal.
   Derselbe Korrekturweg ist zugleich der einzige Weg zur zweiten Sprache -- Textkorrektur und
   Uebersetzung sind fuer das Modell derselbe Vorgang, sie unterscheiden sich nur im
   mitgegebenen Sprachtag.
2. Klassifikation und Business-Code stehen mit der Anlage endgueltig fest. Es gibt keinen
   Umtypisierungs-Weg.
3. Ein Status entsteht dadurch nicht. Die Ontologie kennt fuer Constraint keinen, und diese
   Entscheidung fuegt keinen hinzu.
4. Der Korrekturweg unterliegt demselben Compare-and-Set-Schutz ueber den Head wie jede andere
   korrigierbare Ressource der BC (ADR-014). Constraint bekommt keinen ungeschuetzten Sonderpfad,
   nur weil sein Schreibvolumen klein ist.

## Konsequenzen

**Positiv:** Die Mehrsprachigkeit der BC ist ohne Ausnahme -- kein Ressourcentyp mehr, dessen
Freitext strukturell einsprachig bleiben muesste, und keine Sonderregel, die ein Erfassungsgespraech
sich merken muesste. Ein sachlich falsch formulierter Constraint ist reparierbar, ohne seine
Identitaet und damit alle Verweise auf ihn zu verlieren. Der Schreibpfad der BC wird gleichfoermiger
statt vielfaeltiger: Constraint benutzt denselben Lese-Aendere-Schreibe-Zyklus mit Head-Vergleich wie
Requirement, kein zweiter Mechanismus daneben.

**Negativ / bewusst deferred (YAGNI):** Die Garantie "einmal geschrieben, nie wieder geaendert"
entfaellt. Wer sich auf den Wortlaut eines Constraints verlassen hat, hat nun keine Zusicherung
mehr, dass er derselbe geblieben ist -- die Revisionen werden zwar bei jedem Schreibvorgang
festgehalten, aber kein Lesewerkzeug macht sie sichtbar, sodass eine Textaenderung im Modell
nicht nachvollziehbar ist. Das ist der Preis dieser Entscheidung und zugleich der Grund, warum
die Sichtbarkeit der Aenderungshistorie (#251) an Gewicht gewinnt.

Der vor dieser Entscheidung angelegte Bestand traegt ungetaggte Literale. Er wird nicht migriert,
sondern normalisiert sich nur dort, wo ihn ohnehin jemand unter der Projekt-Standardsprache
anfasst -- und das feldweise, nicht ressourcenweise. Ein Bestand, den niemand mehr anfasst, bleibt
dauerhaft ungetaggt. Das ist bewusst so gewaehlt: ein Migrationswerkzeug fuer eine Handvoll
Ressourcen waere teurer als die Ungleichheit, die es beseitigt.

Ein Constraint mit falscher Klassifikation bleibt nur ueber eine Neuanlage korrigierbar, und die
vergibt einen neuen Code, dem bestehende Verweise nicht folgen. Wer die Klassifikation falsch
setzt, zahlt also weiterhin den vollen Preis -- die Intake-Disziplin gilt unveraendert fort, nur
eben fuer Klassifikation und Code statt fuer den Wortlaut.

## Alternativen

- **Unveraenderlichkeit halten und beide Sprachen in einem Anlage-Aufruf entgegennehmen.**
  Verworfen -- weicht vom Muster aller anderen Schreibwege der BC ab, die genau einen Sprachtag pro
  Aufruf tragen, und laesst den bereits angelegten einsprachigen Bestand dauerhaft unreparierbar.
  Die Unveraenderlichkeit waere formal gewahrt, der Preis dafuer aber ein Sonderweg genau an der
  Stelle, an der Einheitlichkeit den Nutzen ausmacht.
- **Constraint vollstaendig mutierbar machen, Klassifikation eingeschlossen.** Verworfen -- die
  Klassifikation bestimmt das Code-Praefix, ein Wechsel muesste den Code also entweder invalidieren
  oder ihn stillschweigend unter einem anderen Zaehler neu vergeben. Beides bricht Verweise, die
  ausserhalb des Modells in Prosa stehen und keiner Umbenennung folgen.
- **Constraints bewusst einsprachig fuehren.** Verworfen -- waere der einzige Ressourcentyp der BC
  ohne Mehrsprachigkeit, und zwar ohne fachlichen Grund: ein Constraint ist genauso
  uebersetzungsbeduerftig wie ein Requirement, das ihn bindet.
- **Bei Unveraenderlichkeit bleiben und Korrektur ueber Neuanlage samt Abloesekante loesen.**
  Verworfen -- setzt eine Abloesebeziehung zwischen Constraints voraus, die das Metamodell nicht
  kennt, und verlagert eine reine Textkorrektur auf einen Identitaetswechsel. Ein Tippfehler waere
  damit teurer als eine inhaltliche Neubewertung.
