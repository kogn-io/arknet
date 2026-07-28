// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.out;

import java.util.List;

import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;

/**
 * Driven port: supplies the {@code arkreq:} requirement vocabulary as data, backing
 * {@link GetRequirementSchema} (issue #31).
 *
 * <p>Named after the capability ("describe the vocabulary"), not after any technology.
 * Implementations live in adapter modules and read the ontology (a static classpath resource),
 * not the project store - unlike {@link RequirementRepository} this takes no
 * {@link de.hauschel.arknet.kernel.ProjectId}, since the vocabulary does not vary per
 * architecture model.</p>
 */
public interface RequirementSchemaSource {

    /**
     * Returns the requirement vocabulary terms, in a fixed, stable order.
     *
     * @return the schema terms, never {@code null} or empty
     */
    List<RequirementSchemaTerm> schema();
}
