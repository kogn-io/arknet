// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.resource.application.port.in.TextHit;
import de.hauschel.arknet.resource.application.port.out.ResourceReader;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * Resolves the statements of one text search onto the resources they belong to, inside one read
 * transaction.
 *
 * <p>The owner is found from the structure: a match on a fragment child ({@code <resource>#...}) or
 * on a blank node belongs to the resource whose slice holds it; a node that is no resource - no
 * resource type, no business code - is climbed to the node pointing at it, as stores written before
 * owned children were fragments hold their steps and criteria. A node nothing owns is reported under
 * itself, so a store of any age is searched without failing.</p>
 */
final class TextFinder {

    /** Bounds the climb, so a cyclical store graph cannot loop. */
    private static final int MAX_OWNER_HOPS = 5;

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final char FRAGMENT = '#';
    private static final String BLANK_NODE_PREFIX = "_:";

    /** The order of the statements of a search: by subject, property and text, not by store order. */
    static final Comparator<Triple> MATCH_ORDER = Comparator
            .comparing((Triple triple) -> term(triple.getSubject()))
            .thenComparing(triple -> triple.getPredicate().getIRIString())
            .thenComparing(triple -> ((Literal) triple.getObject()).getLexicalForm());

    private final ResourceReader.Snapshot snapshot;
    private final ResourceSchema schema;
    private final BiFunction<IRI, ReadableGraph, String> handles;
    private final Function<IRI, String> names;
    private final Map<IRI, ReadableGraph> slices = new HashMap<>();
    private final Map<IRI, String> handleByOwner = new HashMap<>();

    /**
     * Creates the finder.
     *
     * @param snapshot the read transaction
     * @param schema   the rule data, to tell a resource type from a child
     * @param handles  a resource's handle from its IRI and slice
     * @param names    a property's prefixed name, else its IRI
     */
    TextFinder(final ResourceReader.Snapshot snapshot, final ResourceSchema schema,
            final BiFunction<IRI, ReadableGraph, String> handles, final Function<IRI, String> names) {
        this.snapshot = snapshot;
        this.schema = schema;
        this.handles = handles;
        this.names = names;
    }

    /** The node a matching statement is reported under and how it was reached. */
    record Located(RDFTerm owner, Optional<IRI> via) {
    }

    /**
     * Finds the node a match is reported under.
     *
     * @param match a statement whose object is a literal
     * @return the owner, and the property leading from it to the node holding the match
     */
    Located locate(final Triple match) {
        RDFTerm subject = match.getSubject();
        Optional<IRI> via = Optional.empty();
        IRI current;
        if (subject instanceof BlankNode blank) {
            Optional<IRI> holder = snapshot.holding(blank);
            if (holder.isEmpty()) {
                return new Located(blank, via);
            }
            current = holder.get();
            via = edgeInto(blank, current);
        } else {
            current = (IRI) subject;
        }
        for (int hop = 0; hop <= MAX_OWNER_HOPS; hop++) {
            IRI base = withoutFragment(current);
            if (!base.equals(current)) {
                Optional<IRI> edge = edgeFrom(base, current);
                if (edge.isPresent()) {
                    via = edge;
                }
                current = base;
            }
            if (isResource(current)) {
                return new Located(current, via);
            }
            Optional<Triple> incoming = snapshot.pointingAt(current).stream()
                    .min(Comparator.comparing((Triple triple) -> term(triple.getSubject()))
                            .thenComparing(triple -> triple.getPredicate().getIRIString()));
            if (incoming.isEmpty()) {
                return new Located(current, via);
            }
            via = Optional.of(incoming.get().getPredicate());
            RDFTerm next = incoming.get().getSubject() instanceof BlankNode blank
                    ? snapshot.holding(blank).<RDFTerm>map(iri -> iri).orElse(blank)
                    : incoming.get().getSubject();
            if (!(next instanceof IRI nextIri)) {
                return new Located(next, via);
            }
            current = nextIri;
        }
        return new Located(current, via);
    }

    /**
     * The types of a located owner.
     *
     * @param owner the owner
     * @return its {@code rdf:type} values, empty for a node without a slice
     */
    Set<IRI> typesOf(final RDFTerm owner) {
        return owner instanceof IRI iri ? ReadService.typesOf(iri, slice(iri)) : Set.of();
    }

    /**
     * The handle an owner is reported under: the one {@code resource_get} resolves, so a business
     * code more than one resource carries gives way to the IRI.
     *
     * @param owner the owner
     * @return the handle
     */
    String handleOf(final RDFTerm owner) {
        if (!(owner instanceof IRI iri)) {
            return term(owner);
        }
        return handleByOwner.computeIfAbsent(iri, key -> {
            String handle = handles.apply(key, slice(key));
            return snapshot.withCode(handle).size() > 1 ? key.getIRIString() : handle;
        });
    }

    /**
     * Builds the hit of a match.
     *
     * @param match   the matching statement
     * @param located its owner
     * @param query   the text searched for
     * @return the hit
     */
    TextHit hit(final Triple match, final Located located, final String query) {
        Literal literal = (Literal) match.getObject();
        return new TextHit(handleOf(located.owner()), names.apply(match.getPredicate()),
                located.via().map(names), literal.getLanguageTag(),
                Snippet.around(literal.getLexicalForm(), query));
    }

    private boolean isResource(final IRI iri) {
        ReadableGraph slice = slice(iri);
        if (slice.stream(iri, VocabDct.IDENTIFIER, null).findAny().isPresent()) {
            return true;
        }
        Set<String> resourceTypes = schema.resourceTypes();
        return ReadService.typesOf(iri, slice).stream().anyMatch(type -> resourceTypes.contains(type.getIRIString()));
    }

    /** The property of the resource's slice leading to a child, the first by name if several do. */
    private Optional<IRI> edgeFrom(final IRI resource, final IRI child) {
        return snapshot.pointingAt(child).stream()
                .filter(triple -> triple.getSubject() instanceof IRI subject && withoutFragment(subject).equals(resource)
                        || triple.getSubject() instanceof BlankNode)
                .map(Triple::getPredicate)
                .min(Comparator.comparing(IRI::getIRIString));
    }

    /** The property of a holder's slice whose value is the blank node. */
    private Optional<IRI> edgeInto(final BlankNode node, final IRI holder) {
        return slice(holder).stream(null, null, node)
                .filter(triple -> triple.getSubject() instanceof IRI)
                .map(Triple::getPredicate)
                .min(Comparator.comparing(IRI::getIRIString));
    }

    private ReadableGraph slice(final IRI iri) {
        return slices.computeIfAbsent(iri, snapshot::slice);
    }

    private static IRI withoutFragment(final IRI iri) {
        int fragment = iri.getIRIString().indexOf(FRAGMENT);
        return fragment < 0 ? iri : RDF_FACTORY.createIRI(iri.getIRIString().substring(0, fragment));
    }

    private static String term(final RDFTerm term) {
        if (term instanceof IRI iri) {
            return iri.getIRIString();
        }
        if (term instanceof BlankNode blankNode) {
            return BLANK_NODE_PREFIX + blankNode.uniqueReference();
        }
        return term.toString();
    }

    /**
     * The statements to search under, in the stable order of {@link #MATCH_ORDER}.
     *
     * @param snapshot the read transaction
     * @param query    the text
     * @return the statements with a literal object
     */
    static List<Triple> matches(final ResourceReader.Snapshot snapshot, final String query) {
        List<Triple> found = new ArrayList<>();
        snapshot.literalsContaining(query).stream().filter(triple -> triple.getObject() instanceof Literal)
                .forEach(found::add);
        found.sort(MATCH_ORDER);
        return found;
    }
}
