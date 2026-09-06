// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkproc:} actor vocabulary as Java {@code String} constants -
 * the single source of truth shared by the code that <em>writes</em> them (the actor out-adapter,
 * {@code de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepository}) and the code that
 * <em>reads</em> them: {@code arknet-mcp}'s traceability read path
 * ({@code de.hauschel.arknet.mcp.trace.TraceabilityGraph#actorIris()}), the use-cases component's
 * name-based cross-BC resolution
 * ({@code de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfActorLookup}), and
 * {@code KognioRdfUseCaseRepository}'s validation-only assertion of {@link #ACTOR_TYPE} for
 * {@code arkreq:primaryActor}/{@code supportingActor}'s {@code sh:class} constraint.
 *
 * <p>Same rationale as {@link ArkdddVocabulary}: these are RDF serialization constants, the
 * literal IRI form of ontology classes and a datatype property. The actor core deliberately never
 * sees them - opaque identity keeps IRIs out of the domain - so they are not domain vocabulary in
 * the sense that keeps {@code arknet-shared-kernel} free of such concerns. Being plain
 * {@code String}s they leave this module's RDF4J-freedom untouched.</p>
 *
 * <p><strong>Created for kogn-io/arknet#148.</strong> Before this class, {@link #HUMAN_ACTOR_TYPE}/
 * {@link #SYSTEM_ACTOR_TYPE}/{@link #LEGAL_ACTOR_TYPE}/{@link #GROUP_ACTOR_TYPE} were each
 * declared as a private copy of the same four IRI literals in three places -
 * {@code KognioRdfActorRepository} (the writer), {@code TraceabilityGraph} and
 * {@code KognioRdfActorLookup} (both readers) - the exact drift risk
 * {@link ArkdddVocabulary}/{@link ArkreqVocabulary} already closed for their own namespaces.
 * {@link #ACTOR_TYPE} had the same problem one level up: a fourth private copy in
 * {@code KognioRdfUseCaseRepository} and a fifth in an architecture test.</p>
 *
 * <p><strong>Scope: the whole active module.</strong> This class mirrors the whole active
 * {@code arknet-actor.ttl} module (the Actor taxonomy plus the Role resource and its occupancy
 * edge), the same pattern {@link ArkprovVocabulary}/{@link ArkprjVocabulary}/
 * {@link ArkarchVocabulary} follow and {@link ArkdddVocabulary} adopted alongside them
 * (kogn-io/arknet#148) - held against that ontology by a bidirectional architecture test
 * ({@code arknet-architecture-tests}), which is what the mirror makes possible in the first
 * place.</p>
 */
public final class ArkprocVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/process#";

    /**
     * {@code arkproc:Actor} - someone or something that can act on the system under description,
     * or hold an interest in it, or both; {@code rdfs:subClassOf prov:Agent}. The abstract
     * superclass of the four concrete types below - no adapter ever asserts this type itself, only
     * one of its subclasses, but {@code arkreq:primaryActor}/{@code supportingActor}'s
     * {@code sh:class} constraint targets exactly this class, so
     * {@code KognioRdfUseCaseRepository} asserts it as validation-only context for a referenced
     * actor.
     */
    public static final String ACTOR_TYPE = NAMESPACE + "Actor";

    /** {@code arkproc:HumanActor} - a natural person who participates in the process. */
    public static final String HUMAN_ACTOR_TYPE = NAMESPACE + "HumanActor";

    /** {@code arkproc:SystemActor} - an external system or service that participates in the process. */
    public static final String SYSTEM_ACTOR_TYPE = NAMESPACE + "SystemActor";

    /**
     * {@code arkproc:LegalActor} - a legal person (organization, company, association) that
     * participates in the process, as opposed to a natural person acting on its behalf.
     */
    public static final String LEGAL_ACTOR_TYPE = NAMESPACE + "LegalActor";

    /**
     * {@code arkproc:GroupActor} - a group without a legal form of its own, acting or holding an
     * interest collectively rather than through one identifiable natural or legal person.
     */
    public static final String GROUP_ACTOR_TYPE = NAMESPACE + "GroupActor";

    /**
     * {@code arkproc:Role} - a named function in which someone or something acts or holds an
     * interest, named independently of who fills it (ADR-37). A second, independent resource type
     * in this module, not a subclass of {@link #ACTOR_TYPE}: an actor is rigid, a role is
     * anti-rigid, and an anti-rigid type may not subclass a rigid one.
     */
    public static final String ROLE_TYPE = NAMESPACE + "Role";

    /**
     * {@code arkproc:filledBy} - the optional, multivalued edge from a {@link #ROLE_TYPE} to the
     * {@link #ACTOR_TYPE}(s) that currently occupy it (ADR-37). The specification never reads this
     * edge; only evaluating views display the occupancy.
     */
    public static final String FILLED_BY = NAMESPACE + "filledBy";

    private ArkprocVocabulary() {
    }
}
