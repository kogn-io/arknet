// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Objects;

/**
 * Opaque, unchanging identity of a project, i.e. which architecture model a piece of knowledge
 * belongs to.
 *
 * <p>Value object wrapping the routing key used to address a project's store. It is a
 * <strong>shared kernel</strong> concept: several bounded contexts (requirements,
 * ubiquitous-language, use-cases, bounded-context) all persist into the same per-project dataset
 * and therefore share this identity rather than each inventing its own. The project component
 * ({@code arknet-project}) manages the lifecycle of the thing this identifies, but the identity
 * itself has to be nameable by every context that routes on it - which is why it lives here and
 * not in that component's core, on which no sibling {@code *-core} is allowed to depend.</p>
 *
 * <p><strong>Not a {@link ResourceId}.</strong> A {@code ResourceId} is a subject IRI addressing a
 * resource inside a dataset; a {@code ProjectId} instead <em>becomes</em> the dataset id itself
 * (ADR-016 decision 1 - one dataset holds exactly one project's data). Conflating the two would
 * make a project's identity depend on the very store it identifies, which is backwards: the id has
 * to exist before the dataset it names does. A {@code ProjectId} is therefore a bare opaque
 * string, minted directly by the project component's application service, never derived from
 * anything a client sends, never interpreted.</p>
 *
 * <p><strong>Registered, not derived.</strong> Until ADR-016 this value was computed from the
 * calling client's directory name (the slugged git top-level), which made two identically named
 * directories in different places collapse onto one store - issue #175. It is now minted once and
 * reached through the registered anchors a client presents, see {@link ProjectResolver}. Because
 * it is opaque, its <em>form</em> is deliberately unconstrained beyond non-blankness: ids that
 * grew out of that old slug-based derivation (e.g. {@code "arknet"}) remain valid values and are
 * never migrated or reshaped (ADR-016 decision 5) - they simply gain the anchors they were always
 * reached by.</p>
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
     * constant lives on the domain type rather than in an out-adapter: whether a value is
     * reserved is an invariant that must hold on every construction path, not a serialisation
     * detail an adapter happens to know about. An adapter that mapped {@code ProjectId} to a
     * dataset id would otherwise have to duplicate this check - or worse, omit it - to keep the
     * invariant true.</p>
     */
    public static final String RESERVED_SYSTEM_DATASET = "urn:arknet:system";

    /**
     * The implicit project a call without an origin used to fall back to.
     *
     * @deprecated ADR-016 decision 3 removes the notion of a default entirely - a call whose
     *             project cannot be determined is an error, not a call routed somewhere. Still
     *             here only so this rename stays behaviour-neutral; the switch-over deletes it.
     */
    @Deprecated
    public static final ProjectId DEFAULT = new ProjectId("default");

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
