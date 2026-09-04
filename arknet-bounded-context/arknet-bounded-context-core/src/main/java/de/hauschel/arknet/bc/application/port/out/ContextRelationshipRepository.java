// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: persistence capability the component needs to record a
 * {@link ContextRelationship}.
 *
 * <p>Named after the capability ("store a context relationship"), not after any technology.
 * Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not leak their
 * mechanism into this contract.</p>
 *
 * <p><strong>Pure create, deliberately.</strong> Unlike {@link BoundedContextRepository}, this
 * port has no compare-and-set update and no idempotency/dedup-on-existing-triple check: every
 * call mints and persists a brand-new {@link ContextRelationship}. This matches the CRUD scope of
 * {@code bc_link_context} - recording that a relationship was asserted, not maintaining a single
 * canonical relationship per bounded-context pair. There is consequently no read method on this
 * port either: inspecting created relationships goes through the generic store-wide read path
 * ({@code store_overview}/{@code resource_get}), not a dedicated
 * {@code bc_get_context}/{@code bc_list_context} tool.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a relationship belongs
 * to, exactly as it does for {@link BoundedContextRepository}.</p>
 */
public interface ContextRelationshipRepository {

    /**
     * Persists a brand-new context relationship.
     *
     * @param projectId    the project (architecture model) to store the relationship in
     * @param relationship the relationship to create, already carrying its minted identity
     * @return the persisted relationship (the same value as {@code relationship})
     */
    ContextRelationship create(ProjectId projectId, ContextRelationship relationship);
}
