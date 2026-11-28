// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepositoryFactory;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfRoleRepositoryFactory;
import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfConstraintLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRequirementLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRoleLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.application.UseCaseService;

/**
 * The typed cores the actor and role comparison tests drive, wired over one store the way the
 * composition root wires them: actor and role share one write funnel, the use case core resolves
 * a role through the store.
 *
 * <p>Also holds the one term of the actor register that {@code ArkarchVocabulary} does not carry
 * as a constant ({@code arknet:description}), derived from the namespace of {@code NAME} so the
 * namespace is named once.</p>
 */
final class ActorRegisterCores {

    /** {@code arknet:description} of an actor or a role. */
    static final String DESCRIPTION = ArkarchVocabulary.NAME
            .substring(0, ArkarchVocabulary.NAME.length() - "name".length()) + "description";

    private final ActorService actors;
    private final RoleService roles;
    private final UseCaseService useCases;
    private final DatasetLifecycle lifecycle;

    private ActorRegisterCores(final DatasetLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        UuidResourceIdFactory ids = new UuidResourceIdFactory();
        var funnel = KognioRdfActorRepositoryFactory.buildFunnel(lifecycle, DisplayLocale.DEFAULT);
        ActorRepository actorRepository = KognioRdfActorRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT,
                funnel);
        RoleRepository roleRepository = KognioRdfRoleRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT, funnel);
        this.actors = new ActorService(actorRepository, ids);
        this.roles = new RoleService(roleRepository, actorRepository, ids);
        this.useCases = new UseCaseService(
                KognioRdfUseCaseRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT), ids,
                new KognioRdfRequirementLookup(lifecycle), new KognioRdfRoleLookup(lifecycle),
                new KognioRdfTermLookup(lifecycle), new KognioRdfConstraintLookup(lifecycle));
    }

    static ActorRegisterCores over(final DatasetLifecycle lifecycle) {
        return new ActorRegisterCores(lifecycle);
    }

    ActorService actors() {
        return actors;
    }

    RoleService roles() {
        return roles;
    }

    UseCaseService useCases() {
        return useCases;
    }

    /**
     * Writes a second name in the language of the first straight into the store, past the gate - a
     * record the actor shape ({@code sh:uniqueLang}) rejects, as data written before the shape
     * existed may be.
     *
     * @param project  the project
     * @param actorIri the identity of the actor
     * @param language the language tag both names carry
     */
    void seedSecondNameInTheSameLanguage(final ProjectId project, final String actorIri, final String language) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <" + actorIri + "> <"
                        + ArkarchVocabulary.NAME + "> \"Second name\"@" + language + " } }");
                return null;
            });
        }
    }
}
