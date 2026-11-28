// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.time.Clock;
import java.util.List;

import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepositoryFactory;
import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfBoundedContextLookup;
import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfRequirementLookup;
import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The typed cores an architecture decision record is written through in a comparison, over the
 * store of the test class: {@link AdrService} itself, and the requirement, bounded context and
 * glossary cores whose resources its four edge lists point at. The neighbours only lay the
 * preconditions of a case; what is compared is the decision.
 *
 * <p>The decision core stamps a day it is not told from {@link Clock#systemDefaultZone()}, the
 * clock {@code submit} reads in {@link ComparisonStore} too.</p>
 */
final class AdrNeighbourhood {

    private static final String EN = "en";

    private final AdrService adrs;
    private final RequirementService requirements;
    private final BoundedContextService contexts;
    private final TermService terms;

    private AdrNeighbourhood(final AdrService adrs, final RequirementService requirements,
            final BoundedContextService contexts, final TermService terms) {
        this.adrs = adrs;
        this.requirements = requirements;
        this.contexts = contexts;
        this.terms = terms;
    }

    /**
     * Wires the cores over the store.
     *
     * @param store the store of the test class
     * @return the cores
     */
    static AdrNeighbourhood over(final ComparisonStore store) {
        VersionedWriteFunnel requirementFunnel =
                KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT);
        RequirementService requirements = new RequirementService(
                KognioRdfRequirementRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT,
                        requirementFunnel),
                new UuidResourceIdFactory(),
                new de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup(store.lifecycle()),
                KognioRdfConstraintRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT,
                        requirementFunnel),
                KognioRdfRequirementRepositoryFactory.buildSchemaSource());
        BoundedContextService contexts = new BoundedContextService(
                KognioRdfBoundedContextRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(),
                new de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfTermLookup(store.lifecycle()),
                KognioRdfContextRelationshipRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT));
        TermService terms = new TermService(KognioRdfTermRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT), new UuidResourceIdFactory());
        AdrService adrs = new AdrService(KognioRdfAdrRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), new KognioRdfRequirementLookup(store.lifecycle()),
                new KognioRdfBoundedContextLookup(store.lifecycle()),
                new de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfTermLookup(store.lifecycle()),
                Clock.systemDefaultZone());
        return new AdrNeighbourhood(adrs, requirements, contexts, terms);
    }

    /**
     * @return the decision core under comparison
     */
    AdrService adrs() {
        return adrs;
    }

    /**
     * Adds a functional requirement for a decision to address.
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
     * Adds a bounded context, without a subdomain (kogn-io/arknet#745), for a decision to affect.
     *
     * @param project the project
     * @param name    its name
     * @return the context
     */
    BoundedContext context(final ProjectId project, final String name) {
        return contexts.add(project, new NewBoundedContext(name, "Keeps the " + name + " language.", null,
                "The architecture team", EN), EN);
    }

    /**
     * Adds a glossary term for a decision to use.
     *
     * @param project the project
     * @param label   its label
     * @return the term
     */
    Term term(final ProjectId project, final String label) {
        return terms.add(project, new NewTerm(label, "A word the decisions use for " + label + ".", EN, null,
                List.of()), EN);
    }
}
