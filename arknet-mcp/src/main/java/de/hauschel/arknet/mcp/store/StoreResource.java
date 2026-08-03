// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LocalizedLiteral;

/**
 * A single subject (an IRI, or a blank-node reference - see {@link Triple#subject()}) together
 * with its outgoing statements, plus a few generic display helpers (rdf:type, label, status,
 * priority). {@link #label(DisplayLocale)} recognises well-known predicates common across the
 * arknet bounded contexts (dcterms:title, skos:prefLabel, rdfs:label) and never depends on a
 * specific one.
 * {@link #status()}/{@link #priority()} are a deliberate, bounded exception: they hardcode the
 * requirements-BC's {@code arkreq:status}/{@code arkreq:priority} predicates rather than a
 * structural (e.g. SHACL-driven) mechanism - a resource without them, or a future BC's analogous
 * field under a different namespace, still renders, just without that hint/pill.
 *
 * @param iri      the subject IRI, or a blank-node reference
 * @param outgoing all statements whose subject is {@link #iri}
 */
public record StoreResource(String iri, List<Triple> outgoing) {

    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final List<String> LABEL_PREDICATES = List.of(
            "http://purl.org/dc/terms/title",
            "http://www.w3.org/2004/02/skos/core#prefLabel",
            "http://www.w3.org/2000/01/rdf-schema#label");
    private static final String STATUS_PREDICATE = "https://w3id.org/arknet/requirements#status";
    private static final String PRIORITY_PREDICATE = "https://w3id.org/arknet/requirements#priority";
    private static final String IDENTIFIER_PREDICATE = "http://purl.org/dc/terms/identifier";

    public StoreResource {
        Objects.requireNonNull(iri, "iri");
        outgoing = List.copyOf(outgoing);
    }

    /**
     * @return the object IRIs of all {@code rdf:type} statements, sorted alphabetically. Sorted,
     *         not encounter order (issue #150): the statements come from a {@code SELECT
     *         DISTINCT} with no {@code ORDER BY}, so encounter order is not guaranteed stable
     *         across calls - a report diffed against an earlier run would otherwise show a type
     *         list reshuffled for no real change.
     */
    public List<String> types() {
        return outgoing.stream()
                .filter(t -> RDF_TYPE.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .sorted()
                .toList();
    }

    /**
     * The label among dcterms:title / skos:prefLabel / rdfs:label, in that priority order - the
     * first of the three predicates that carries any literal at all wins, and {@code locale}
     * then picks which one of that predicate's (possibly language-tagged) literals to show, via
     * its documented fallback chain. Never disagrees with itself between calls for the same
     * {@code locale}: {@link DisplayLocale#select} is deterministic.
     *
     * @param locale the display language to select among same-predicate candidates
     */
    public Optional<String> label(DisplayLocale locale) {
        Objects.requireNonNull(locale, "locale");
        for (String predicate : LABEL_PREDICATES) {
            List<LocalizedLiteral> candidates = literalsOf(predicate);
            if (!candidates.isEmpty()) {
                return locale.select(candidates).map(LocalizedLiteral::value);
            }
        }
        return Optional.empty();
    }

    /** @return the local name of the {@code arkreq:status} object IRI, if present. */
    public Optional<String> status() {
        return firstObjectIri(STATUS_PREDICATE).map(StoreResource::localName);
    }

    /** @return the local name of the {@code arkreq:priority} object IRI, if present. */
    public Optional<String> priority() {
        return firstObjectIri(PRIORITY_PREDICATE).map(StoreResource::localName);
    }

    /**
     * @return the {@code dcterms:identifier} literal of this resource, if present. Used as the
     *         human-readable handle fallback when the subject IRI cannot be shortened to a
     *         CURIE (e.g. an opaque, kernel-minted {@link de.hauschel.arknet.kernel.ResourceId}).
     */
    public Optional<String> identifier() {
        return firstLiteral(IDENTIFIER_PREDICATE);
    }

    private Optional<String> firstLiteral(String predicate) {
        return outgoing.stream()
                .filter(t -> predicate.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Literal.class::isInstance)
                .map(o -> ((RdfNode.Literal) o).lexicalForm())
                .findFirst();
    }

    /** @return every literal for {@code predicate}, as {@link DisplayLocale}-selectable candidates. */
    private List<LocalizedLiteral> literalsOf(String predicate) {
        return outgoing.stream()
                .filter(t -> predicate.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Literal.class::isInstance)
                .map(o -> (RdfNode.Literal) o)
                .map(literal -> literal.languageTag() == null
                        ? LocalizedLiteral.untagged(literal.lexicalForm())
                        : LocalizedLiteral.tagged(literal.lexicalForm(), literal.languageTag()))
                .toList();
    }

    private Optional<String> firstObjectIri(String predicate) {
        return outgoing.stream()
                .filter(t -> predicate.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .findFirst();
    }

    /** @return the local name (after the last {@code #} or {@code /}) of an IRI. */
    public static String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return cut >= 0 && cut + 1 < iri.length() ? iri.substring(cut + 1) : iri;
    }
}
