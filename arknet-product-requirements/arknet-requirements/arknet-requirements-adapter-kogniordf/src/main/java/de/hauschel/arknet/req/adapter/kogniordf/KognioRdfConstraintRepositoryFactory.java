// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.Objects;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;

/**
 * Assembles a {@link KognioRdfConstraintRepository} over a concrete kognio-rdf dataset
 * lifecycle, alongside {@link KognioRdfRequirementRepositoryFactory} in the same package (issue
 * #223) - {@link de.hauschel.arknet.req.domain.Constraint} lives inside the same requirements
 * bounded context, so this factory does not build its own SHACL write-gate/write funnel: it
 * reuses whatever {@link WriteFunnel} the caller already built via
 * {@link KognioRdfRequirementRepositoryFactory#buildFunnel}, since both {@code requirements-shapes.ttl}
 * and {@code arknet-requirements.ttl} already cover {@code Constraint}'s shapes and the
 * {@code oslc_rm:constrainedBy} edge - including, since issue #313, the {@code sh:uniqueLang}
 * shapes that make {@code title}/{@code statement} multilingual.
 */
public final class KognioRdfConstraintRepositoryFactory {

    private KognioRdfConstraintRepositoryFactory() {
    }

    /**
     * Assembles a constraint repository over an already-created dataset lifecycle and an
     * already-built {@link WriteFunnel} - the same funnel instance the composition root wires
     * into {@link KognioRdfRequirementRepositoryFactory#over(DatasetLifecycle, DisplayLocale)}'s
     * sibling requirement repository, per this method's own contract that {@code funnel} is
     * shared rather than rebuilt.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference the read paths select a multilingual
     *                      constraint's {@code title}/{@code statement} with (issue #313) - the
     *                      same value the composition root passes to the sibling requirement
     *                      repository
     * @param funnel        the shared write funnel both of
     *                      {@link KognioRdfConstraintRepository}'s writes run through (see
     *                      {@link KognioRdfRequirementRepositoryFactory#buildFunnel})
     * @return a ready-to-use {@link ConstraintRepository}
     */
    public static ConstraintRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale,
            WriteFunnel funnel) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        Objects.requireNonNull(funnel, "funnel");
        return new KognioRdfConstraintRepository(lifecycle, displayLocale, funnel);
    }
}
