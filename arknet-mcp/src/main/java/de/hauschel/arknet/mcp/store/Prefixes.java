// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.persistence.ArkprovVocabulary;

/**
 * Bidirectional CURIE / IRI resolver over a fixed set of namespace bindings.
 *
 * <p>Domain-agnostic: it only maps namespace prefixes to IRIs and back, so the generic
 * store report can shorten IRIs for display ({@link #toCurie(String)}) and expand a handle
 * an agent typed ({@link #toIri(String)}) - e.g. {@code req:FR-1} <-> {@code
 * https://w3id.org/arknet/model/requirement/FR-1} - without knowing anything about
 * requirements or glossary terms. The bindings cover arknet's instance namespaces and the
 * standard vocabularies actually used by the bounded contexts.</p>
 *
 * <p>Resolution rules for {@link #toIri(String)}:</p>
 * <ul>
 *   <li>a full IRI (contains {@code ://}) resolves to itself;</li>
 *   <li>{@code prefix:local} with a known prefix expands to {@code namespace + local};</li>
 *   <li>otherwise, if the part before the colon is a syntactically valid RFC 3986 URI scheme
 *       (e.g. {@code urn}, {@code mailto}) the whole token resolves to itself - a non-hierarchical
 *       IRI never contains {@code ://} but is still self-authoritative (issue #305);</li>
 *   <li>an unknown, non-scheme-shaped prefix, or a bare token without a colon, resolves to empty
 *       (the caller may then try a business-id lookup).</li>
 * </ul>
 */
public final class Prefixes {

    /** Base of arknet instance-data IRIs (model resources, as opposed to vocabulary terms). */
    public static final String MODEL_INSTANCE_BASE = "https://w3id.org/arknet/model/";

    /**
     * Base of arknet's opaque resource identities as minted by {@code UuidResourceIdFactory}
     * (arknet-shared-kernel) since the opaque-{@code ResourceId} refactor: a
     * flat {@code https://w3id.org/arknet/id/<uuid>}, no bounded-context or type segment - the
     * type lives in {@code rdf:type}. This is the only base <em>model resources</em> are minted
     * under since that refactor; {@link #MODEL_INSTANCE_BASE} predates it and is no longer
     * produced. It is not the only base anything is minted under: the shared write funnel mints
     * revision and activity identities under its own bases, deliberately outside this
     * one because a revision is infrastructure rather than a model resource. Only the revision
     * base is bound here (as {@code rev:}, since {@code resource_history} does render revision
     * IRIs, issue #251) - the activity base has no reader here to shorten it for.
     */
    public static final String INSTANCE_BASE = "https://w3id.org/arknet/id/";

    /** A single {@code prefix -> namespace} binding. */
    public record Prefix(String prefix, String namespace) {
        public Prefix {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(namespace, "namespace");
        }
    }

    private final List<Prefix> bindings;

    /**
     * Creates a resolver over the given bindings.
     *
     * @param bindings the namespace bindings (must not be {@code null})
     */
    public Prefixes(List<Prefix> bindings) {
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }

    /**
     * The default arknet bindings: instance namespaces (requirement, term, actor) plus the
     * standard vocabularies the bounded contexts write (arkreq, arkproc, arkarch, arkddd,
     * arknet core, skos, dcterms, rdf, rdfs, xsd), plus one revision-instance binding
     * ({@code rev:}).
     *
     * <p>Still no {@code arkprov:}/{@code prov:} predicate bindings: {@code StoreReader}
     * excludes the provenance graph's predicates/head pointer from every model read path,
     * so none of them ever reaches a renderer or the prefix legend - a binding for
     * something unreachable is a promise the read path does not keep. The one exception is
     * {@code rev:}, bound to {@code ArkprovVocabulary#REVISION_IRI_BASE}: {@code
     * resource_history} (issue #251) is the one read path that deliberately does surface a
     * revision's own IRI, so it needs this binding to shorten one for display, the same way
     * every other reachable IRI is shortened.</p>
     *
     * @return the default resolver
     */
    public static Prefixes defaults() {
        return new Prefixes(List.of(
                new Prefix("req", MODEL_INSTANCE_BASE + "requirement/"),
                new Prefix("term", MODEL_INSTANCE_BASE + "term/"),
                new Prefix("act", MODEL_INSTANCE_BASE + "actor/"),
                new Prefix("rev", ArkprovVocabulary.REVISION_IRI_BASE),
                new Prefix("arknet", "https://w3id.org/arknet/core#"),
                new Prefix("arkreq", "https://w3id.org/arknet/requirements#"),
                new Prefix("arkproc", "https://w3id.org/arknet/process#"),
                new Prefix("arkarch", "https://w3id.org/arknet/architecture#"),
                new Prefix("arkddd", "https://w3id.org/arknet/ddd#"),
                new Prefix("skos", "http://www.w3.org/2004/02/skos/core#"),
                new Prefix("dcterms", "http://purl.org/dc/terms/"),
                new Prefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
                new Prefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
                new Prefix("xsd", "http://www.w3.org/2001/XMLSchema#")));
    }

    /** @return the bindings, longest namespace first (so the most specific one shortens). */
    public List<Prefix> bindings() {
        return bindings.stream()
                .sorted((a, b) -> Integer.compare(b.namespace().length(), a.namespace().length()))
                .toList();
    }

    /**
     * Expands a CURIE or full IRI to an absolute IRI.
     *
     * @param curieOrIri a full IRI, or a {@code prefix:local} CURIE
     * @return the absolute IRI, or empty if the token is a bare id (no colon) or uses a prefix
     *         that is neither known nor a syntactically valid URI scheme
     */
    public Optional<String> toIri(String curieOrIri) {
        Objects.requireNonNull(curieOrIri, "curieOrIri");
        String token = curieOrIri.strip();
        if (token.startsWith("<") && token.endsWith(">") && token.length() >= 2) {
            token = token.substring(1, token.length() - 1);
        }
        if (token.contains("://")) {
            return Optional.of(token);
        }
        int colon = token.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String prefix = token.substring(0, colon);
        String local = token.substring(colon + 1);
        Optional<String> bound = bindings.stream()
                .filter(b -> b.prefix().equals(prefix))
                .findFirst()
                .map(b -> b.namespace() + local);
        if (bound.isPresent()) {
            return bound;
        }
        // Not a known CURIE prefix. A non-hierarchical IRI such as urn:uuid:... or mailto:...
        // never contains "://" but is still a complete, self-authoritative IRI - its "prefix" is
        // an RFC 3986 URI scheme (ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )), not a CURIE prefix
        // to look up. Without this, such a token fell through to "unknown prefix" even though it
        // was never meant to be one (issue #305).
        if (isUriScheme(prefix)) {
            return Optional.of(token);
        }
        return Optional.empty();
    }

    /** {@code true} if {@code candidate} is a syntactically valid RFC 3986 URI scheme. */
    private static boolean isUriScheme(String candidate) {
        if (candidate.isEmpty() || !isAsciiLetter(candidate.charAt(0))) {
            return false;
        }
        for (int i = 1; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (!isAsciiLetter(c) && !Character.isDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Shortens an absolute IRI to a {@code prefix:local} CURIE using the most specific
     * matching binding, or returns the IRI unchanged when no binding matches.
     *
     * @param iri the absolute IRI
     * @return the CURIE, or the original IRI if unbound
     */
    public String toCurie(String iri) {
        Objects.requireNonNull(iri, "iri");
        return bindings().stream()
                .filter(b -> iri.startsWith(b.namespace()))
                .findFirst()
                .map(b -> b.prefix() + ":" + iri.substring(b.namespace().length()))
                .orElse(iri);
    }
}
