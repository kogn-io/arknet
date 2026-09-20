// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Application service behind {@code adr_check}: reads a project's decisions once and reports what is
 * mechanically decidable about them (kogn-io/arknet#387).
 *
 * <p><strong>Its own service, deliberately not a method on {@link AdrService}.</strong> Every path
 * there writes or is a read in service of a write; this one only ever reads, changes nothing and has
 * no transaction of its own to protect. Keeping it apart means a corpus-wide rule can never
 * accidentally grow into a write gate, and the class that carries the lifecycle invariants does not
 * grow a second, unrelated reason to change.</p>
 *
 * <p><strong>It borrows {@link ListAdrs} rather than the repository.</strong> The check needs both
 * supersession directions and the merged {@code relatedTo} view to tell "the prose names a peer the
 * graph does not connect to" from "the peer names this record instead" - which is exactly what
 * {@link AdrDetail} already carries and what a second, raw repository read would have to
 * reconstruct. The price is one in-port depending on another of the same hexagon; the gain is that
 * this service never sees a store, and a test hands it a corpus as a lambda.</p>
 *
 * <p><strong>What it does not do.</strong> Bundling (does this record carry more than one
 * decision?), contradiction between two records, and whether a consequence says anything are
 * readings, not matches - {@link CheckAdrs#NOT_CHECKED} names them, and no heuristic here pretends
 * otherwise. The suspicion class exists for the same reason in the other direction: a tracker
 * number, an address literal or a duplicate-looking title is often exactly what the decision is
 * about, so those are reported as something to look at rather than as a defect.</p>
 */
public class AdrCheckService implements CheckAdrs {

    private static final String CODE_PREFIX = "ADR-";

    /** An issue, PR or commit number, in either the bare {@code #123} or the {@code repo#123} form. */
    private static final Pattern TRACKER_REFERENCE = Pattern.compile("#\\d+");

    /**
     * A decision code named in running text. Matched literally rather than by running number, so a
     * zero-padded {@code ADR-099} - another numbering space, not this one - is reported as unheld
     * instead of silently reading as {@code ADR-99}.
     */
    private static final Pattern DECISION_REFERENCE = Pattern.compile("\\bADR-\\d+\\b");

    /**
     * A host, address or port literal: an IPv4 address with an optional port, {@code localhost} with
     * an optional port, an explicit port inside a URL authority, or the words "port 8080". Narrow on
     * purpose - a bare four-digit number is a year as often as a port, and a suspicion nobody
     * believes is worse than none.
     */
    private static final Pattern ADDRESS_LITERAL = Pattern.compile(
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b"
                    + "|\\blocalhost(?::\\d{1,5})?\\b"
                    + "|://[^\\s/]+:\\d{1,5}"
                    + "|\\bport\\s+\\d{2,5}\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Status prose in english and german - the skill's own R4 list in both languages a decision here
     * is written in. Deliberately not extended with near-synonyms: every word added widens a
     * suspicion class that is already the most easily dismissed one.
     */
    private static final Pattern STATUS_PROSE = Pattern.compile(
            "\\b(?:today|currently|not\\s+yet|so\\s+far|heute|derzeit|bisher|noch\\s+nicht)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Splits a title into words: everything that is neither a letter nor a digit separates. */
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Words that carry no subject and would let two unrelated titles look alike. Function words
     * only, english and german, plus the two verbs nearly every decision title starts with.
     */
    private static final Set<String> TITLE_NOISE_WORDS = Set.of(
            "the", "and", "for", "with", "without", "from", "into", "this", "that", "its", "not",
            "all", "any", "one", "two", "use", "uses", "using", "keep", "per", "via",
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "eines",
            "und", "oder", "fuer", "für", "von", "vom", "mit", "ohne", "als", "aus", "auf",
            "nicht", "kein", "keine", "statt", "über", "ueber");

    /** A word this short is a preposition or an article in both languages this scans. */
    private static final int TITLE_NOISE_WORD_LENGTH = 2;

    /**
     * How much of two titles' subject words must coincide before the pair is worth a look, as a
     * Jaccard ratio. Two thirds would already miss "... under docs" against "... under docs/";
     * anything much lower pairs up every title that mentions the store.
     */
    private static final double DUPLICATE_TITLE_SIMILARITY = 0.6;

    /** One shared word is a coincidence in a corpus about one system; two is a subject. */
    private static final int DUPLICATE_MIN_SHARED_WORDS = 2;

    /**
     * Report order: by decision (running number, so {@code ADR-10} follows {@code ADR-2} rather than
     * preceding it as string order would have it), then by rule, then by where in the record it sits.
     * Deterministic down to the last tie so that two runs over an unchanged corpus produce the same
     * text and a diff of two reports shows what actually changed.
     */
    private static final Comparator<Finding> REPORT_ORDER =
            Comparator.<Finding>comparingInt(finding -> CodeCounter.runningNumber(CODE_PREFIX,
                            finding.code().value()))
                    .thenComparing(finding -> finding.code().value())
                    .thenComparing(finding -> finding.rule().ordinal())
                    .thenComparing(Finding::field, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Finding::evidence, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final ListAdrs listAdrs;

    /**
     * Creates the service over the listing in-port it reads the corpus through.
     *
     * @param listAdrs this hexagon's own listing port, the one read path this check has
     */
    public AdrCheckService(ListAdrs listAdrs) {
        this.listAdrs = Objects.requireNonNull(listAdrs, "listAdrs");
    }

    @Override
    public CheckReport check(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        List<AdrDetail> corpus = listAdrs.list(projectId, displayLocale);
        Set<String> heldCodes = new LinkedHashSet<>();
        corpus.forEach(detail -> heldCodes.add(detail.adr().code().value()));
        // A LinkedHashSet, not a list: the same pattern twice in one field is one finding, and a
        // reader who has already been told about it is not told again.
        Set<Finding> findings = new LinkedHashSet<>();
        corpus.forEach(detail -> findings.addAll(checkRecord(detail, heldCodes)));
        findings.addAll(duplicateTitleSuspicions(corpus));
        List<Finding> ordered = new ArrayList<>(findings);
        ordered.sort(REPORT_ORDER);
        return new CheckReport(corpus.size(), ordered);
    }

    /** Every finding about one decision: the record-level facts first, then its prose. */
    private static List<Finding> checkRecord(AdrDetail detail, Set<String> heldCodes) {
        Adr adr = detail.adr();
        List<Finding> findings = new ArrayList<>();
        if (adr.status() == AdrStatus.PROPOSED && adr.decisionDate() != null) {
            findings.add(new Finding(adr.code(), Rule.DECISION_DATE_WHILE_PROPOSED, null, null));
        }
        if (adr.consequences().isEmpty()) {
            findings.add(new Finding(adr.code(), Rule.NO_CONSEQUENCE, null, null));
        }
        if (adr.consideredOptions().isEmpty()) {
            findings.add(new Finding(adr.code(), Rule.NO_CONSIDERED_OPTION, null, null));
        } else if (wasTaken(adr.status()) && noneChosen(adr.consideredOptions())) {
            // Only from ACCEPTED on (kogn-io/arknet#427): PROPOSED means precisely that nothing has
            // been chosen yet, and a record with no options at all is already reported above - saying
            // it twice would make one gap look like two.
            findings.add(new Finding(adr.code(), Rule.NO_CHOSEN_OPTION, null, null));
        }
        if (adr.addressesRequirements().isEmpty() && adr.affectsContexts().isEmpty()) {
            findings.add(new Finding(adr.code(), Rule.NO_OUTWARD_EDGE, null, null));
        }
        Set<String> linkedCodes = linkedCodes(detail);
        for (ProseField field : proseFields(adr)) {
            findings.addAll(scanProse(adr.code(), field, heldCodes, linkedCodes));
        }
        return findings;
    }

    /**
     * The record's readable text, field by field, each labelled the way {@code adr_get} names it so
     * that a finding can be walked to without counting.
     */
    private static List<ProseField> proseFields(Adr adr) {
        List<ProseField> fields = new ArrayList<>();
        fields.add(new ProseField("name", adr.name(), false));
        fields.add(new ProseField("context", adr.context(), false));
        fields.add(new ProseField("decision", adr.decision(), true));
        for (Consequence consequence : adr.consequences()) {
            fields.add(new ProseField("consequence " + consequence.position(), consequence.statement(), true));
        }
        for (ConsideredOption option : adr.consideredOptions()) {
            fields.add(new ProseField("considered option " + option.position(), option.name(), false));
            fields.add(new ProseField("considered option " + option.position() + " rationale",
                    option.rationale(), false));
        }
        return fields;
    }

    /** Everything one text field has to say, in the fixed order the report renders it in. */
    private static List<Finding> scanProse(AdrCode code, ProseField field, Set<String> heldCodes,
            Set<String> linkedCodes) {
        List<Finding> findings = new ArrayList<>();
        for (String named : matches(DECISION_REFERENCE, field.text())) {
            if (named.equals(code.value())) {
                // A record naming its own code is telling a reader which record they are in, not
                // pointing anywhere - there is no edge for it and none is missing.
                continue;
            }
            if (!heldCodes.contains(named)) {
                findings.add(new Finding(code, Rule.UNRESOLVED_DECISION_REFERENCE, field.name(), named));
            } else if (!linkedCodes.contains(named)) {
                findings.add(new Finding(code, Rule.DECISION_REFERENCE_WITHOUT_EDGE, field.name(), named));
            }
        }
        for (String tracker : matches(TRACKER_REFERENCE, field.text())) {
            findings.add(new Finding(code, Rule.TRACKER_REFERENCE, field.name(), tracker));
        }
        for (String literal : matches(ADDRESS_LITERAL, field.text())) {
            findings.add(new Finding(code, Rule.ADDRESS_LITERAL, field.name(), literal));
        }
        if (field.statusProseMatters()) {
            for (String adverb : matches(STATUS_PROSE, field.text())) {
                findings.add(new Finding(code, Rule.STATUS_PROSE, field.name(), adverb));
            }
        }
        return findings;
    }

    /**
     * Pairs of decisions whose titles name near enough the same subject, reported once - on the later
     * of the two, naming the earlier. The later one is the one a reader would fold away, and
     * reporting the pair from both ends would double a corpus-wide count that is a hint to begin
     * with.
     */
    private static List<Finding> duplicateTitleSuspicions(List<AdrDetail> corpus) {
        List<AdrDetail> byNumber = corpus.stream()
                .sorted(Comparator.<AdrDetail>comparingInt(
                                detail -> CodeCounter.runningNumber(CODE_PREFIX, detail.adr().code().value()))
                        .thenComparing(detail -> detail.adr().code().value()))
                .toList();
        List<Set<String>> titleWords = byNumber.stream().map(detail -> subjectWords(detail.adr().name())).toList();
        List<Finding> findings = new ArrayList<>();
        for (int later = 1; later < byNumber.size(); later++) {
            for (int earlier = 0; earlier < later; earlier++) {
                if (nameTheSameSubject(titleWords.get(later), titleWords.get(earlier))) {
                    findings.add(new Finding(byNumber.get(later).adr().code(), Rule.POSSIBLE_DUPLICATE, null,
                            byNumber.get(earlier).adr().code().value()));
                }
            }
        }
        return findings;
    }

    /** The subject-carrying words of a title: lowercased, function words and two-letter words gone. */
    private static Set<String> subjectWords(String title) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : TITLE_SEPARATOR.split(title.toLowerCase(Locale.ROOT))) {
            if (word.length() > TITLE_NOISE_WORD_LENGTH && !TITLE_NOISE_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static boolean nameTheSameSubject(Set<String> left, Set<String> right) {
        Set<String> shared = new LinkedHashSet<>(left);
        shared.retainAll(right);
        if (shared.size() < DUPLICATE_MIN_SHARED_WORDS) {
            return false;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return (double) shared.size() / union.size() >= DUPLICATE_TITLE_SIMILARITY;
    }

    /**
     * Every peer decision this record is connected to, in either direction and by either relation -
     * what "the prose names a decision the graph does not connect to" is measured against. All three
     * lists count: a reader who follows a supersession reaches the peer just as surely as one who
     * follows a cross-reference.
     */
    private static Set<String> linkedCodes(AdrDetail detail) {
        Set<String> codes = new LinkedHashSet<>();
        detail.supersedes().forEach(code -> codes.add(code.value()));
        detail.supersededBy().forEach(code -> codes.add(code.value()));
        detail.relatedTo().forEach(code -> codes.add(code.value()));
        return codes;
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * Whether the decision has been taken at all. The three statuses beyond {@code ACCEPTED} are only
     * reachable from it, so each one means a choice was made once - and a made decision without a
     * chosen option is the same gap in all three.
     */
    private static boolean wasTaken(AdrStatus status) {
        return status == AdrStatus.ACCEPTED || status == AdrStatus.DEPRECATED || status == AdrStatus.SUPERSEDED;
    }

    private static boolean noneChosen(List<ConsideredOption> options) {
        return options.stream().noneMatch(option -> option.outcome() == OptionOutcome.CHOSEN);
    }

    /**
     * One readable text field of a record.
     *
     * @param name                the field's name as {@code adr_get} shows it
     * @param text                its content in the language variant this run read
     * @param statusProseMatters  whether "today"/"currently" is a smell here - true for the decision
     *                            and its consequences, false for the context, which legitimately
     *                            describes the situation at the time (skill rule R4)
     */
    private record ProseField(String name, String text, boolean statusProseMatters) {
    }
}
