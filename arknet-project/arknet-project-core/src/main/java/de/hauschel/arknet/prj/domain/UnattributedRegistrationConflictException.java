// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

/**
 * Thrown by {@code ProjectRegistry#register} when a write lost a real store commit conflict that
 * neither uniqueness guard ({@link AnchorAlreadyRegisteredException},
 * {@link DuplicateProjectLabelException}) can explain - the residual case
 * {@code KognioRdfProjectRegistry#attributeLostRegistration} names when it has nothing truthful
 * to attribute the loss to.
 *
 * <p>Deliberately technology-neutral: the out-adapter is the only place allowed to name the
 * store's own conflict type ({@code io.kogn.rdf.dataset.ConcurrencyConflictException}), since the
 * bounded context cores stay free of RDF technology by construction (see
 * {@code arknet-architecture-tests}' dependency rules). This exception carries that raw conflict
 * as its {@link #getCause() cause} without naming its type, so the signal still reaches
 * {@code ProjectService} without the core depending on the store's technology.</p>
 *
 * <p>Safe to retry: the candidate behind a lost {@code register} is a freshly minted, never-reused
 * identity whose write was fully rolled back, so repeating the same call is the same write, not a
 * new one, and runs through both uniqueness guards again - a real, now-visible collision is
 * reported correctly instead of being retried past.</p>
 */
public class UnattributedRegistrationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param conflict the store's own conflict, preserved as {@link #getCause()}
     */
    public UnattributedRegistrationConflictException(Throwable conflict) {
        super("registration lost a store commit conflict that neither the anchor nor the label "
                + "uniqueness guard explains", conflict);
    }
}
