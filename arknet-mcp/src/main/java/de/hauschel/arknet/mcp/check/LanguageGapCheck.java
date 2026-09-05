// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Finds the fields of a project's model that do not carry every language the project undertakes to
 * maintain (kogn-io/arknet#412).
 *
 * <p><strong>One structural rule, no per-bounded-context field list.</strong> The rule is: for
 * every subject in the model and every predicate on which that subject already carries at least
 * one <em>language-tagged</em> literal, every maintained language for which no literal exists is a
 * gap. Nothing here names a requirement, a term or a use case, and nothing has to be added when a
 * bounded context grows a field - the same property that lets {@code store_overview} report a
 * model it does not know the shape of. The alternative, a maintained list of "these predicates are
 * multilingual", is a second place to keep in step with eight write paths, and its silence would
 * be indistinguishable from a clean result.</p>
 *
 * <p><strong>What it cannot see, by construction.</strong> A field carrying no language-tagged
 * literal at all - a single untagged literal written before an omitted {@code language} argument
 * resolved to the project default (issue #258), or a field never written in any language - looks
 * exactly like a field that is simply not multilingual. There is nothing in the data to tell those
 * two apart without the per-bounded-context list this check deliberately does not keep, so such a
 * field is not reported. That limit travels in the tool's own description and in its output rather
 * than only here.</p>
 *
 * <p>Language tags are compared case-insensitively: the store is written through {@code
 * LanguageTag#canonicalize} on every path, but a store-first literal that predates that
 * canonicalization must still count as the language it names, or the check would report a gap the
 * reader can see is filled.</p>
 */
public final class LanguageGapCheck {

    private static final String IDENTIFIER_PREDICATE = "http://purl.org/dc/terms/identifier";
    private static final String NOTATION_PREDICATE = "http://www.w3.org/2004/02/skos/core#notation";

    /**
     * The local name every positioned child resource carries its 1-based position under -
     * {@code arkreq:position} in the requirements/use-cases vocabulary, {@code arknet:position} in
     * the core one. Matched by local name rather than by full IRI so a further namespace minting
     * its own {@code position} needs no change here; the concept is the same one either way.
     */
    private static final String POSITION_LOCAL_NAME = "position";

    /**
     * One field of one resource that is missing at least one maintained language.
     *
     * @param subjectIri        the subject the field sits on - an IRI, or a blank-node reference
     * @param handle            a human-readable handle for {@code subjectIri} (a business code, or
     *                          an owning resource's code plus this child's position), or
     *                          {@code null} when the resource offers neither and the renderer
     *                          should fall back to the IRI itself
     * @param typeLocalName     the local name of the resource's primary {@code rdf:type}, or
     *                          {@code null} for an untyped subject
     * @param predicateIri      the predicate whose literals are incomplete
     * @param missingLanguages  the maintained languages this predicate carries no literal for,
     *                          in the order the project declared them
     */
    public record Gap(String subjectIri, String handle, String typeLocalName, String predicateIri,
            List<String> missingLanguages) {

        public Gap {
            Objects.requireNonNull(subjectIri, "subjectIri");
            Objects.requireNonNull(predicateIri, "predicateIri");
            missingLanguages = List.copyOf(missingLanguages);
        }
    }

    private LanguageGapCheck() {
    }

    /**
     * Runs the check over one snapshot.
     *
     * @param snapshot            the model snapshot, already free of the provenance and identity
     *                            graphs {@code StoreReader} hides
     * @param maintainedLanguages the languages the project undertakes to maintain; an empty set
     *                            yields no gaps, because there is then no target state to compare
     *                            against - a caller must present that as "not checked", never as
     *                            "nothing found"
     * @return every gap, ordered by handle/IRI then predicate so two runs over an unchanged store
     *         produce the same report
     */
    public static List<Gap> run(final StoreSnapshot snapshot, final List<String> maintainedLanguages) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(maintainedLanguages, "maintainedLanguages");
        if (maintainedLanguages.isEmpty()) {
            return List.of();
        }
        final Map<String, Reference> owners = ownersByChild(snapshot);
        final List<Gap> gaps = new ArrayList<>();
        for (final StoreResource resource : snapshot.resources()) {
            final String handle = handleOf(resource, owners, snapshot);
            final String typeLocalName = typeLocalNameOf(resource);
            taggedLanguagesByPredicate(resource).forEach((predicate, tags) -> {
                final List<String> missing = maintainedLanguages.stream()
                        .filter(language -> !containsIgnoringCase(tags, language))
                        .toList();
                if (!missing.isEmpty()) {
                    gaps.add(new Gap(resource.iri(), handle, typeLocalName, predicate, missing));
                }
            });
        }
        return gaps.stream()
                .sorted(Comparator.comparing((Gap gap) -> gap.handle() == null ? gap.subjectIri() : gap.handle())
                        .thenComparing(Gap::predicateIri))
                .toList();
    }

    /**
     * The language tags each predicate of {@code resource} already carries a literal for - only
     * predicates with at least one <em>tagged</em> literal appear, which is exactly the "this field
     * is multilingual" signal this check has and the blind spot named in the class javadoc.
     */
    private static Map<String, Set<String>> taggedLanguagesByPredicate(final StoreResource resource) {
        final Map<String, Set<String>> byPredicate = new TreeMap<>();
        for (final Triple triple : resource.outgoing()) {
            if (triple.object() instanceof RdfNode.Literal literal && literal.languageTag() != null
                    && !literal.languageTag().isBlank()) {
                byPredicate.computeIfAbsent(triple.predicate(), key -> new LinkedHashSet<>())
                        .add(literal.languageTag());
            }
        }
        return byPredicate;
    }

    private static boolean containsIgnoringCase(final Set<String> tags, final String language) {
        return tags.stream().anyMatch(tag -> tag.equalsIgnoreCase(language));
    }

    /** An edge pointing at a child resource: who points, and under which predicate. */
    private record Reference(String subjectIri, String predicateIri) {
    }

    /**
     * Indexes every subject that is pointed at by exactly one other subject - the shape every
     * positioned child resource (a use-case step, an acceptance criterion, a consequence, a
     * considered option) has, since each belongs to one owner. A subject reached from two owners is
     * deliberately left out: it has no single owner to be named under, and guessing one would put a
     * wrong code in the report.
     */
    private static Map<String, Reference> ownersByChild(final StoreSnapshot snapshot) {
        final Map<String, List<Reference>> incoming = new LinkedHashMap<>();
        for (final StoreResource resource : snapshot.resources()) {
            for (final Triple triple : resource.outgoing()) {
                if (triple.object() instanceof RdfNode.Resource target) {
                    incoming.computeIfAbsent(target.iri(), key -> new ArrayList<>())
                            .add(new Reference(resource.iri(), triple.predicate()));
                }
            }
        }
        final Map<String, Reference> single = new LinkedHashMap<>();
        incoming.forEach((target, references) -> {
            if (references.size() == 1) {
                single.put(target, references.get(0));
            }
        });
        return single;
    }

    /**
     * A resource's readable handle: its own business code where it has one, otherwise - for a
     * positioned child resource with no code of its own - its owner's code plus the edge and
     * position it hangs off, e.g. {@code FR-1 acceptanceCriterion#2}. {@code null} when neither is
     * available, which leaves the renderer to show the IRI rather than invent an address.
     */
    private static String handleOf(final StoreResource resource, final Map<String, Reference> owners,
            final StoreSnapshot snapshot) {
        final Optional<String> own = codeOf(resource);
        if (own.isPresent()) {
            return own.get();
        }
        final Reference owner = owners.get(resource.iri());
        if (owner == null) {
            return null;
        }
        final Optional<String> ownerCode = snapshot.resources().stream()
                .filter(candidate -> candidate.iri().equals(owner.subjectIri()))
                .findFirst()
                .flatMap(LanguageGapCheck::codeOf);
        if (ownerCode.isEmpty()) {
            return null;
        }
        final String edge = StoreResource.localName(owner.predicateIri());
        return positionOf(resource)
                .map(position -> ownerCode.get() + " " + edge + "#" + position)
                .orElseGet(() -> ownerCode.get() + " " + edge);
    }

    /** A resource's own business code: {@code dcterms:identifier}, else {@code skos:notation}. */
    private static Optional<String> codeOf(final StoreResource resource) {
        final Optional<String> identifier = firstLiteral(resource, IDENTIFIER_PREDICATE);
        return identifier.isPresent() ? identifier : firstLiteral(resource, NOTATION_PREDICATE);
    }

    private static Optional<String> positionOf(final StoreResource resource) {
        return resource.outgoing().stream()
                .filter(triple -> POSITION_LOCAL_NAME.equals(StoreResource.localName(triple.predicate())))
                .map(Triple::object)
                .filter(RdfNode.Literal.class::isInstance)
                .map(object -> ((RdfNode.Literal) object).lexicalForm())
                .findFirst();
    }

    private static Optional<String> firstLiteral(final StoreResource resource, final String predicate) {
        return resource.outgoing().stream()
                .filter(triple -> predicate.equals(triple.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Literal.class::isInstance)
                .map(object -> ((RdfNode.Literal) object).lexicalForm())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    /** The local name of the resource's primary {@code rdf:type}, or {@code null} when untyped. */
    private static String typeLocalNameOf(final StoreResource resource) {
        final String primaryType = StoreSnapshot.primaryType(resource);
        return StoreSnapshot.UNTYPED.equals(primaryType) ? null : StoreResource.localName(primaryType);
    }
}
