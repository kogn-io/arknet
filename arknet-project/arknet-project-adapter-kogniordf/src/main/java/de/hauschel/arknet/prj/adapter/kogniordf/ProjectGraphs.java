// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Shared serialisation of a {@link Project} to RDF triples, and the two IRI mappings that
 * serialisation is built on. The single place both {@link KognioRdfProjectRegistry} (the
 * registry, in the reserved system dataset) and {@link KognioRdfProjectSelfDescription} (the
 * self-description, in the project's own dataset) build the identical triple shape from - so the
 * two write paths ADR-016 deliberately keeps separate (decisions 6 and 7) cannot drift apart
 * triple-by-triple the way two near-identical read/write paths in sibling out-adapters already
 * have (issues #80/#81).
 *
 * <p><strong>Deterministic anchor identity is the load-bearing decision of this adapter.</strong>
 * An anchor's subject IRI is not minted (no {@code ResourceIdFactory}, no
 * {@code UUID.randomUUID()}) but derived, deterministically, as {@link #ANCHOR_IRI_BASE} followed
 * by the SHA-256 hex digest of the anchor's opaque value - the value <em>alone</em>; the anchor's
 * {@code AnchorType} plays no part in {@link #anchorIri}. This is not an oversight: it is exactly
 * why {@link Anchor#equals} and {@link Anchor#hashCode} are likewise overridden to compare the
 * value alone (see that record's javadoc). Storage identity here and domain equality there must
 * stay cut the same way - if the domain ever considered the same value under two different types
 * to be two distinct anchors while this method still mapped them onto one storage node, a caller
 * attaching an already-registered value under a different type would look new to the domain, sail
 * past the in-transaction uniqueness check in {@link KognioRdfProjectRegistry}, and collide only
 * once the resulting duplicate anchor node reaches the SHACL gate.</p>
 *
 * <ul>
 *   <li><strong>The same anchor value always names the same node.</strong> A replace-by-identity
 *       rewrite that keeps an anchor across an update (see {@link KognioRdfProjectRegistry}'s
 *       class javadoc) deletes and re-adds bit-identical triples for it, rather than retiring an
 *       old node and minting a fresh one - there is no orphaned "previous anchor node" a rewrite
 *       could ever leave behind in the first place, because the node a kept anchor writes to
 *       never changes.</li>
 *   <li><strong>Cross-project uniqueness becomes an existence check.</strong> ADR-016 decision 4's
 *       central invariant - an anchor belongs to at most one project - reduces to
 *       {@code DatasetTx#contains(graph, anchorIri, null, null)} on this one, predictable
 *       subject: "does any project already have triples for the node this exact anchor value
 *       would produce". {@code contains} is conflict-protected under {@code SERIALIZABLE} in a
 *       way a SPARQL {@code ASK} guard on a not-yet-known IRI is not (ADR-013 Nachtrag) - a
 *       property that only holds because the node's identity is knowable <em>before</em> the
 *       write runs, which a randomly minted IRI could never offer.</li>
 *   <li><strong>Not a security control.</strong> SHA-256 here is a stable, collision-resistant
 *       mapping from an arbitrary opaque string (a filesystem path can contain spaces or
 *       characters such as {@code <}/{@code >} that are not IRIREF-safe) onto an IRIREF-safe
 *       suffix - nothing about the hash is meant to hide or authenticate the anchor value, which
 *       is why {@link #anchorIri} hashes it in the clear and {@link ArkprjVocabulary#ANCHOR_VALUE}
 *       still stores the original string in full, unhashed.</li>
 * </ul>
 *
 * <p>A {@link ProjectId} becomes a subject IRI the same way {@code ResourceId} does elsewhere in
 * this codebase, but the mapping is adapter-only serialisation, not domain knowledge: the project
 * core never sees an IRI at all (its identity is opaque, see {@link ProjectId}'s own javadoc), so
 * both {@link #projectIri} and its inverse {@link #projectIdOf} live here, not in the core.</p>
 */
final class ProjectGraphs {

    static final String PROJECT_IRI_BASE = "https://w3id.org/arknet/project/";
    static final String ANCHOR_IRI_BASE = "https://w3id.org/arknet/anchor/";

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final HexFormat HEX = HexFormat.of();

    private ProjectGraphs() {
    }

    /** Maps a project's opaque identity to the subject IRI both write paths persist it under. */
    static String projectIri(ProjectId id) {
        return PROJECT_IRI_BASE + id.value();
    }

    /** Inverse of {@link #projectIri}: strips the base prefix to recover the opaque identity. */
    static ProjectId projectIdOf(String iri) {
        if (!iri.startsWith(PROJECT_IRI_BASE)) {
            throw new IllegalStateException(
                    "not a project IRI (missing prefix " + PROJECT_IRI_BASE + "): " + iri);
        }
        return new ProjectId(iri.substring(PROJECT_IRI_BASE.length()));
    }

    /**
     * Maps an anchor's opaque value to its deterministic subject IRI - see the class javadoc for
     * why this is a hash rather than a minted identity.
     */
    static String anchorIri(Anchor anchor) {
        return ANCHOR_IRI_BASE + HEX.formatHex(sha256(anchor.value()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of the algorithm set every JDK's default security providers are
            // required to offer (JCA baseline) - this can only fail if the runtime itself is
            // misconfigured, never as a function of the anchor value.
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }

    /**
     * Builds the RDF graph {@code project}'s current state maps to: the project's own type,
     * {@code dcterms:identifier} (the label, see the ontology's "identity vs. label" note - there
     * is deliberately no separate label property) and {@code arkprj:anchor} edges, plus each
     * anchor node's own {@code arkprj:Anchor} type, {@code arkprj:anchorValue} and
     * {@code arkprj:anchorType} triples.
     *
     * <p>Identical for both write paths (see class javadoc): the returned graph is written into
     * different named graphs of different datasets by its two callers, but never differs in
     * shape - both simply hand this graph to {@code tx.add}.</p>
     */
    static Graph buildGraph(Project project) {
        Graph graph = RDF_FACTORY.createGraph();
        IRI projectIri = RDF_FACTORY.createIRI(projectIri(project.id()));
        graph.add(projectIri, VocabRdf.TYPE, RDF_FACTORY.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        graph.add(projectIri, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(project.label()));
        for (Anchor anchor : project.anchors()) {
            IRI anchorIri = RDF_FACTORY.createIRI(anchorIri(anchor));
            graph.add(projectIri, RDF_FACTORY.createIRI(ArkprjVocabulary.ANCHOR), anchorIri);
            graph.add(anchorIri, VocabRdf.TYPE, RDF_FACTORY.createIRI(ArkprjVocabulary.ANCHOR_CLASS));
            graph.add(anchorIri, RDF_FACTORY.createIRI(ArkprjVocabulary.ANCHOR_VALUE),
                    RDF_FACTORY.createLiteral(anchor.value()));
            graph.add(anchorIri, RDF_FACTORY.createIRI(ArkprjVocabulary.ANCHOR_TYPE),
                    RDF_FACTORY.createIRI(anchorTypeIri(anchor.type())));
        }
        return graph;
    }

    static String anchorTypeIri(AnchorType type) {
        return switch (type) {
            case PATH -> ArkprjVocabulary.PATH_ANCHOR;
            case URL -> ArkprjVocabulary.URL_ANCHOR;
            case UUID -> ArkprjVocabulary.UUID_ANCHOR;
        };
    }

    static AnchorType anchorTypeFromIri(String iri) {
        if (ArkprjVocabulary.PATH_ANCHOR.equals(iri)) {
            return AnchorType.PATH;
        }
        if (ArkprjVocabulary.URL_ANCHOR.equals(iri)) {
            return AnchorType.URL;
        }
        if (ArkprjVocabulary.UUID_ANCHOR.equals(iri)) {
            return AnchorType.UUID;
        }
        throw new IllegalStateException("unexpected anchor type " + iri);
    }
}
