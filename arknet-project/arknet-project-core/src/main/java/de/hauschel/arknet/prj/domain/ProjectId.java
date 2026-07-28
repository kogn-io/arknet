// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

/**
 * Opaque, unchanging identity of a {@link Project}.
 *
 * <p>Unlike {@code BoundedContextId}/{@code RequirementId} and their siblings, this identity is
 * <strong>not</strong> a {@link de.hauschel.arknet.kernel.ResourceId} minted by a {@code
 * ResourceIdFactory}. A {@code ResourceId} is a subject IRI addressing a resource inside a
 * dataset; a {@code ProjectId} instead becomes the dataset id itself (ADR-016 decision 1 - one
 * dataset holds exactly one project's data). Conflating the two would make a project's identity
 * depend on the very store it identifies, which is backwards: the id has to exist before the
 * dataset it names does. A {@code ProjectId} is therefore a bare opaque string, minted directly
 * by the application service (see {@code ProjectService#register}), never derived, never
 * interpreted.</p>
 *
 * <p>Because it is opaque, its <em>form</em> is deliberately unconstrained beyond
 * non-blankness. Newly registered projects mint a UUID, but pre-existing ids that grew out of the
 * old slug-based derivation (e.g. {@code "arknet"}) remain valid values and are never migrated or
 * reshaped (ADR-016 decision 5): they simply keep the directory paths they always had as
 * {@link Anchor}s.</p>
 *
 * @param value the opaque identity value, never {@code null} or blank, and never the reserved
 *              {@link #RESERVED_SYSTEM_DATASET} value
 */
public record ProjectId(String value) {

    /**
     * The dataset id the project registry itself lives in (ADR-016 decision 6).
     *
     * <p>A project registered under this id would write its own data into the registry's own
     * management dataset, corrupting the very index that is supposed to describe it. This
     * constant lives here, in the core domain type, rather than in an out-adapter: whether a
     * value is reserved is a domain invariant the core must enforce on every construction path,
     * not a serialisation detail an adapter happens to know about. An adapter that mapped
     * {@code ProjectId} to a dataset id would otherwise have to duplicate this check - or worse,
     * omit it - to keep the invariant true.</p>
     */
    public static final String RESERVED_SYSTEM_DATASET = "urn:arknet:system";

    public ProjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProjectId must not be blank");
        }
        if (value.equals(RESERVED_SYSTEM_DATASET)) {
            throw new IllegalArgumentException(
                    "ProjectId must not be the reserved system dataset id '" + RESERVED_SYSTEM_DATASET + "'");
        }
    }
}
