// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

/**
 * The kind of integration relationship between two bounded contexts, following the DDD context
 * mapping patterns (Evans, Vernon).
 *
 * <p>Maps 1:1 onto the eight {@code arkddd:RelationshipType} individuals the ontology enumerates.
 * A {@link ContextRelationship} carries exactly one of these; this enum lists exactly those eight
 * values, so a caller can never be offered a relationship type the ontology does not enumerate.
 * Purely descriptive vocabulary - which type applies to a given pair of bounded contexts is a
 * judgement call left entirely to the interviewing agent or user, never inferred here.</p>
 */
public enum RelationshipType {

    /** Both teams coordinate closely as equals; shared success or shared failure. */
    PARTNERSHIP,

    /** Both teams share a subset of code/model that requires close coordination to change. */
    SHARED_KERNEL,

    /** Downstream is a customer, upstream a supplier - upstream considers downstream's needs. */
    CUSTOMER_SUPPLIER,

    /** Downstream adopts upstream's model as-is, with no translation layer of its own. */
    CONFORMIST,

    /** Downstream protects its own model behind a translation layer against upstream's. */
    ANTICORRUPTION_LAYER,

    /** Upstream offers a defined, stable protocol serving several downstream consumers. */
    OPEN_HOST_SERVICE,

    /** Upstream publishes a formal exchange format, often paired with OPEN_HOST_SERVICE. */
    PUBLISHED_LANGUAGE,

    /** No integration at all - a deliberate decoupling of the two contexts. */
    SEPARATE_WAYS
}
