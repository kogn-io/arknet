package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Driving port: register a new requirement.
 *
 * <p>Backs the MVP tool {@code req_add}. Identity assignment
 * ({@code FR-N}/{@code NFR-N}) and initial status are policy of the
 * implementing application service.</p>
 */
public interface AddRequirement {

    /**
     * Adds a new requirement.
     *
     * @param command the data describing the requirement to create
     * @return the persisted requirement including its assigned identity
     */
    Requirement add(NewRequirement command);

    /**
     * Input data for {@link #add(NewRequirement)}.
     *
     * @param title short human-readable summary
     * @param type  functional vs. non-functional classification
     */
    record NewRequirement(String title, RequirementType type) {
    }
}
