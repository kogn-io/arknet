// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.util.List;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepositoryFactory;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfRoleRepositoryFactory;
import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfConstraintLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRequirementLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRoleLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The typed cores a use case is written through in a comparison, over the store of the test class:
 * {@link UseCaseService} itself, and the requirement, constraint, glossary and actor-register cores
 * whose resources a use case points at. The neighbours only lay the preconditions of a case; what
 * is compared is the use case.
 */
final class UseCaseNeighbourhood {

    private static final String EN = "en";

    private final UseCaseService useCases;
    private final RequirementService requirements;
    private final ConstraintService constraints;
    private final TermService terms;
    private final ActorService actors;
    private final RoleService roles;

    private UseCaseNeighbourhood(final UseCaseService useCases, final RequirementService requirements,
            final ConstraintService constraints, final TermService terms, final ActorService actors,
            final RoleService roles) {
        this.useCases = useCases;
        this.requirements = requirements;
        this.constraints = constraints;
        this.terms = terms;
        this.actors = actors;
        this.roles = roles;
    }

    /**
     * Wires the cores over the store.
     *
     * @param store the store of the test class
     * @return the cores
     */
    static UseCaseNeighbourhood over(final ComparisonStore store) {
        VersionedWriteFunnel requirementFunnel =
                KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT);
        ConstraintRepository constraintRepository = KognioRdfConstraintRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT, requirementFunnel);
        RequirementService requirements = new RequirementService(
                KognioRdfRequirementRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT,
                        requirementFunnel),
                new UuidResourceIdFactory(),
                new de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup(store.lifecycle()),
                constraintRepository, KognioRdfRequirementRepositoryFactory.buildSchemaSource());
        ConstraintService constraints = new ConstraintService(constraintRepository, new UuidResourceIdFactory());
        TermService terms = new TermService(KognioRdfTermRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT), new UuidResourceIdFactory());
        VersionedWriteFunnel actorFunnel =
                KognioRdfActorRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT);
        ActorRepository actorRepository = KognioRdfActorRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT,
                actorFunnel);
        ActorService actors = new ActorService(actorRepository, new UuidResourceIdFactory());
        RoleService roles = new RoleService(KognioRdfRoleRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT, actorFunnel), actorRepository, new UuidResourceIdFactory());
        UseCaseService useCases = new UseCaseService(
                KognioRdfUseCaseRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), new KognioRdfRequirementLookup(store.lifecycle()),
                new KognioRdfRoleLookup(store.lifecycle()), new KognioRdfTermLookup(store.lifecycle()),
                new KognioRdfConstraintLookup(store.lifecycle()));
        return new UseCaseNeighbourhood(useCases, requirements, constraints, terms, actors, roles);
    }

    /**
     * @return the use case core under comparison
     */
    UseCaseService useCases() {
        return useCases;
    }

    /**
     * Adds a functional requirement for a step to realise.
     *
     * @param project the project
     * @param title   its title
     * @return the requirement
     */
    Requirement requirement(final ProjectId project, final String title) {
        return requirements.add(project, new NewRequirement(title, "The store keeps " + title + ".",
                "Callers rely on it.", RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null,
                List.of("The store answers with " + title + "."), EN), EN);
    }

    /**
     * Adds a technical constraint for a use case to be constrained by.
     *
     * @param project the project
     * @param title   its title
     * @return the constraint
     */
    Constraint constraint(final ProjectId project, final String title) {
        return constraints.add(project, new NewConstraint(title, "Runs on " + title + ".",
                ConstraintType.TECHNICAL, null), EN);
    }

    /**
     * Adds a glossary term for a use case to use.
     *
     * @param project the project
     * @param label   its label
     * @return the term
     */
    Term term(final ProjectId project, final String label) {
        return terms.add(project, new NewTerm(label, "A word the use cases use for " + label + ".", EN), EN);
    }

    /**
     * Adds a role, without an occupant.
     *
     * @param project the project
     * @param name    its name
     * @return the role
     */
    Role role(final ProjectId project, final String name) {
        return roles.add(project, new NewRole(name, null, List.of(), EN), EN).role();
    }

    /**
     * Adds an actor, which is no role.
     *
     * @param project the project
     * @param name    its name
     * @return the actor
     */
    Actor actor(final ProjectId project, final String name) {
        return actors.add(project, new NewActor(ActorType.HUMAN, name, null, EN), EN);
    }
}
