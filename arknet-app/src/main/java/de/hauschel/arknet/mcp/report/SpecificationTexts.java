// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

/**
 * The fixed texts of {@link SpecificationRenderer} - headings, labels, hints, the help dialog - in
 * the two languages the report speaks. A small table rather than an i18n framework: the report
 * shows one language at a time, German when the effective display language is German and English
 * otherwise.
 *
 * <p>Constants ending in {@code _HTML} hold fixed, trusted markup and are emitted unescaped; every
 * other value is plain text (or an HTML entity written out) and is escaped like any content.</p>
 */
enum SpecificationTexts {

    PAGE_TITLE("Spezifikation", "Specification"),
    MENU_BUTTON_HTML("&#9776; Inhalt", "&#9776; Contents"),
    MENU_OPEN("Inhaltsverzeichnis \u00f6ffnen", "Open table of contents"),
    HELP_BUTTON("? Hilfe", "? Help"),
    TOC_TITLE("Inhalt", "Contents"),
    TOC_LABEL("Inhaltsverzeichnis", "Table of contents"),
    TOC_COLLAPSE("Inhaltsverzeichnis einklappen", "Collapse table of contents"),
    TOC_EXPAND("Inhaltsverzeichnis aufklappen", "Expand table of contents"),
    GRIP_TOC(
            "Breite des Inhaltsverzeichnisses \u00e4ndern",
            "Resize the table of contents"),
    GRIP_DETAIL("Breite des Detailbereichs \u00e4ndern", "Resize the detail pane"),
    DETAIL_LABEL("Detailbereich", "Detail pane"),
    DETAIL_RAIL("Details", "Details"),
    DETAIL_COLLAPSE("Detailbereich einklappen", "Collapse detail pane"),
    DETAIL_EXPAND("Detailbereich aufklappen", "Expand detail pane"),
    DETAIL_CLOSE("Detailbereich schlie\u00dfen", "Close detail pane"),
    BACK_HTML("&larr; Zur\u00fcck", "&larr; Back"),
    SHOW_IN_DOCUMENT("im Dokument anzeigen", "show in document"),
    DETAIL_HINT(
            "Klicken Sie auf einen Verweis, um ihn hier zu sehen.",
            "Click a reference to see it here."),
    OPEN_DETAIL(
            "Ganze Erkl\u00e4rung rechts \u00f6ffnen",
            "Open the full explanation on the right"),
    CLOSE("Schlie\u00dfen", "Close"),
    CH_INTRO("Einleitung", "Introduction"),
    CH_PARTICIPANTS("Beteiligte", "Participants"),
    CH_ACTORS("Akteure", "Actors"),
    CH_ROLES("Rollen", "Roles"),
    CH_USE_CASES("Abl\u00e4ufe", "Processes"),
    CH_FUNCTIONAL("Funktionale Anforderungen", "Functional requirements"),
    CH_QUALITY("Qualit\u00e4tsanforderungen", "Quality requirements"),
    CH_CONSTRAINTS("Rahmenbedingungen", "Constraints"),
    CH_GLOSSARY("Begriffsverzeichnis", "Glossary"),
    NONE_ACTORS(
            "F\u00fcr dieses Projekt sind keine Akteure erfasst.",
            "No actors are recorded for this project."),
    NONE_ROLES(
            "F\u00fcr dieses Projekt sind keine Rollen erfasst.",
            "No roles are recorded for this project."),
    NONE_USE_CASES(
            "F\u00fcr dieses Projekt sind keine Abl\u00e4ufe erfasst.",
            "No processes are recorded for this project."),
    NONE_FUNCTIONAL(
            "F\u00fcr dieses Projekt sind keine funktionalen Anforderungen erfasst.",
            "No functional requirements are recorded for this project."),
    NONE_QUALITY(
            "F\u00fcr dieses Projekt sind keine Qualit\u00e4tsanforderungen erfasst.",
            "No quality requirements are recorded for this project."),
    NONE_CONSTRAINTS(
            "F\u00fcr dieses Projekt sind keine Rahmenbedingungen erfasst.",
            "No constraints are recorded for this project."),
    NONE_GLOSSARY(
            "F\u00fcr dieses Projekt sind keine Begriffe erfasst.",
            "No terms are recorded for this project."),
    INTRO_DESCRIBES(
            "Dieses Dokument beschreibt das Projekt",
            "This document describes the project"),
    INTRO_HINT_HTML(
            "<p>Das Dokument enth\u00e4lt die Beteiligten, die Abl\u00e4ufe, die Anforderungen an das System sowie die Rahmenbedingungen und ein Begriffsverzeichnis. Funktionale Anforderungen tragen eine Kennung wie FR-7, Qualit\u00e4tsanforderungen eine Kennung mit dem Anfang NFR; ein Verweis auf eine Kennung f\u00fchrt zur zugeh\u00f6rigen Stelle im Dokument. Fachbegriffe, die im Begriffsverzeichnis erkl\u00e4rt sind, sind im Text punktiert unterstrichen und f\u00fchren zu ihrer Erkl\u00e4rung.</p>",
            "<p>This document contains the participants, the processes, the requirements on the system, the constraints and a glossary. Functional requirements carry an identifier such as FR-7, quality requirements an identifier starting with NFR; a reference to an identifier leads to the matching place in the document. Technical terms explained in the glossary are underlined with dots in the text and lead to their explanation.</p>"),
    PRIORITY_HINT_HTML(
            "<p>Die Priorit\u00e4t einer Anforderung ist mit einem von vier W\u00f6rtern angegeben: <em>Muss</em> bedeutet, dass die Anforderung unverzichtbar ist; <em>Soll</em>, dass sie wichtig ist, aber im Notfall zur\u00fcckgestellt werden kann; <em>Kann</em>, dass sie w\u00fcnschenswert ist; <em>Nicht</em>, dass sie bewusst nicht umgesetzt wird.</p>",
            "<p>The priority of a requirement is given as one of four words: <em>Must</em> means the requirement is indispensable; <em>Should</em> that it is important but can be postponed in an emergency; <em>Could</em> that it is desirable; <em>Won't</em> that it is deliberately not going to be implemented.</p>"),
    GOAL("Ziel", "Goal"),
    SCOPE("Geltungsbereich", "Scope"),
    TRIGGER("Ausl\u00f6ser", "Trigger"),
    PRECONDITION("Vorbedingung", "Precondition"),
    RESULT("Ergebnis", "Result"),
    ROLES_INVOLVED("Beteiligte Rollen", "Participating roles"),
    FLOW("Ablauf", "Flow"),
    EXTENSIONS("Abweichungen", "Deviations"),
    REALISED_BY("Umgesetzt durch Anforderungen", "Realised by requirements"),
    USED_IN_FLOWS("Genutzt in Abl\u00e4ufen", "Used in processes"),
    INVOLVED_IN_FLOWS("Beteiligt an Abl\u00e4ufen", "Involved in processes"),
    ROLE_PRIMARY("Hauptrolle", "primary role"),
    ROLE_SUPPORTING("wirkt mit", "supporting role"),
    FILLED_BY("Besetzt durch", "Filled by"),
    RATIONALE("Warum", "Rationale"),
    QUALITY_CATEGORY("Qualit\u00e4tskategorie", "Quality category"),
    ACCEPTANCE("Abnahmekriterien", "Acceptance criteria"),
    PRIORITY("Priorit\u00e4t", "Priority"),
    STATUS("Status", "Status"),
    KIND_ACTOR("Akteur", "Actor"),
    KIND_ROLE("Rolle", "Role"),
    KIND_USE_CASE("Ablauf", "Process"),
    KIND_FUNCTIONAL("Funktionale Anforderung", "Functional requirement"),
    KIND_QUALITY("Qualit\u00e4tsanforderung", "Quality requirement"),
    KIND_CONSTRAINT("Rahmenbedingung", "Constraint"),
    KIND_TERM("Begriff", "Term"),
    PRIORITY_MUST("Muss", "Must"),
    PRIORITY_SHOULD("Soll", "Should"),
    PRIORITY_COULD("Kann", "Could"),
    PRIORITY_WONT("Nicht", "Won't"),
    STATUS_PROPOSED("Vorgeschlagen", "Proposed"),
    STATUS_ACCEPTED("Abgestimmt", "Accepted"),
    FAILURE_NOTICE(
            "Hinweis: Dieses Dokument ist unvollst\u00e4ndig, weil folgende Teile des Modells nicht gelesen werden konnten:",
            "Notice: this document is incomplete because the following parts of the model could not be read:"),
    HELP_TITLE("Hilfe zum Lesen", "Reading help"),
    HELP_BODY_HTML(
            "<h3>Aufbau</h3><p>Das Dokument hat sieben Kapitel. Links sehen Sie das Inhaltsverzeichnis. Ein Klick darauf springt zur Stelle. Das Kapitel, in dem Sie gerade lesen, ist dort hervorgehoben.</p>\n<h3>Unterstrichene W\u00f6rter</h3><p>Manche W\u00f6rter sind gepunktet unterstrichen. Das sind Fachbegriffe. Halten Sie die Maus dar\u00fcber, dann erscheint die Erkl\u00e4rung. Auf dem Handy oder Tablet tippen Sie das Wort an. Ein zweites Antippen zeigt die ganze Erkl\u00e4rung rechts.</p>\n<h3>Kennungen wie FR-21</h3><p>Kennungen und Titel in blauer Schrift sind Verweise. Ein Klick darauf zeigt den Abschnitt rechts, ohne dass Sie die Stelle im Text verlassen. FR steht f\u00fcr eine Anforderung. UC steht f\u00fcr einen Ablauf.</p>\n<h3>Zur\u00fcck</h3><p>Rechts oben im Detailbereich gibt es den Knopf \u201eZur\u00fcck\u201c. Er f\u00fchrt zur vorigen Ansicht. Mit \u201eim Dokument anzeigen\u201c springt die Mitte zu dieser Stelle. Auf kleinen Bildschirmen schlie\u00dfen Sie den Detailbereich mit dem Kreuz.</p>\n<h3>Muss, Soll, Kann</h3><p><strong>Muss</strong> hei\u00dft: unverzichtbar. <strong>Soll</strong> hei\u00dft: wichtig, aber im Notfall sp\u00e4ter. <strong>Kann</strong> hei\u00dft: w\u00fcnschenswert. <strong>Nicht</strong> hei\u00dft: bewusst nicht vorgesehen.</p>\n<h3>Spalten anpassen</h3><p>Die Spalten links und rechts k\u00f6nnen Sie breiter oder schmaler ziehen. Fassen Sie dazu den senkrechten Strich an der Kante. Mit den Pfeiltasten geht es auch. Ein Doppelklick auf den Strich stellt die Breite zur\u00fcck. Mit dem kleinen Pfeil oben in der Spalte klappen Sie sie ein. Ein Klick auf die schmale Leiste klappt sie wieder auf.</p>\n<h3>Drucken</h3><p>W\u00e4hlen Sie im Browser \u201eDrucken\u201c (Strg+P oder Cmd+P). Beim Drucken verschwinden die Seitenleisten. Das Dokument wird der Reihe nach auf A4 gedruckt, mit dem Inhaltsverzeichnis vorn.</p>",
            "<h3>Structure</h3><p>The document has seven chapters. On the left you see the table of contents. A click on an entry jumps to that place. The chapter you are currently reading is highlighted there.</p>\n<h3>Underlined words</h3><p>Some words are underlined with dots. These are technical terms. Hold the mouse over one and its explanation appears. On a phone or tablet, tap the word. A second tap shows the full explanation on the right.</p>\n<h3>Identifiers such as FR-21</h3><p>Identifiers and titles in blue are references. A click shows the section on the right without leaving your place in the text. FR stands for a requirement. UC stands for a process.</p>\n<h3>Back</h3><p>At the top of the detail pane there is a &ldquo;Back&rdquo; button. It returns to the previous view. &ldquo;Show in document&rdquo; makes the middle column jump to that place. On small screens, close the detail pane with the cross.</p>\n<h3>Must, Should, Could</h3><p><strong>Must</strong> means: indispensable. <strong>Should</strong> means: important, but can be postponed in an emergency. <strong>Could</strong> means: desirable. <strong>Won't</strong> means: deliberately not planned.</p>\n<h3>Adjusting the columns</h3><p>You can drag the left and right columns wider or narrower. Grab the vertical line at the edge. The arrow keys work too. A double click on the line resets the width. The small arrow at the top of a column collapses it. A click on the narrow bar expands it again.</p>\n<h3>Printing</h3><p>Choose &ldquo;Print&rdquo; in your browser (Ctrl+P or Cmd+P). The side columns disappear when printing. The document is printed in order on A4, with the table of contents first.</p>");

    private final String german;
    private final String english;

    SpecificationTexts(final String german, final String english) {
        this.german = german;
        this.english = english;
    }

    /**
     * @param isGerman whether the report is rendered in German
     * @return the text in the requested language
     */
    String in(final boolean isGerman) {
        return isGerman ? german : english;
    }
}
