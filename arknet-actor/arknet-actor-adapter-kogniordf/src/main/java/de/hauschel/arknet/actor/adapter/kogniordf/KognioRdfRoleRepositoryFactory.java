// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import java.util.Objects;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Assembles a {@link KognioRdfRoleRepository} over a concrete kognio-rdf dataset lifecycle,
 * alongside {@link KognioRdfActorRepositoryFactory} in the same package (ADR-37/
 * kogn-io/arknet#405) - {@link de.hauschel.arknet.actor.domain.Role} lives inside the same actor
 * bounded context, so this factory does not build its own SHACL write-gate/write funnel: it
 * reuses whatever {@link WriteFunnel} the caller already built via
 * {@link KognioRdfActorRepositoryFactory#buildFunnel}, since both {@code actor-shapes.ttl} and
 * {@code arknet-actor.ttl} already cover {@code Role}'s shape and the {@code arkproc:filledBy}
 * edge. Mirrors {@code KognioRdfConstraintRepositoryFactory} exactly.
 */
public final class KognioRdfRoleRepositoryFactory {

    private KognioRdfRoleRepositoryFactory() {
    }

    /**
     * Assembles a role repository over an already-created dataset lifecycle and an already-built
     * {@link WriteFunnel} - the same funnel instance the composition root wires into
     * {@link KognioRdfActorRepositoryFactory#over(DatasetLifecycle, WriteFunnel)}'s sibling actor
     * repository, per this method's own contract that {@code funnel} is shared rather than
     * rebuilt.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference selecting which {@code arknet:name}/
     *                      {@code arknet:description} the read paths surface for a multilingual
     *                      role - the same value the composition root passes to the sibling actor
     *                      repository's factory method, {@link KognioRdfActorRepositoryFactory
     *                      #over(DatasetLifecycle, DisplayLocale)}
     * @param funnel        the shared write funnel both of {@link KognioRdfRoleRepository}'s
     *                      writes run through (see {@link KognioRdfActorRepositoryFactory
     *                      #buildFunnel})
     * @return a ready-to-use {@link RoleRepository}
     */
    public static RoleRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        Objects.requireNonNull(funnel, "funnel");
        return new KognioRdfRoleRepository(lifecycle, displayLocale, funnel);
    }
}
