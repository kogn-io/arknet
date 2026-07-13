package de.hauschel.arknet.req.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;

/**
 * Driving port: fetch a single requirement by identity.
 *
 * <p>Backs the MVP tool {@code req_get}.</p>
 */
public interface GetRequirement {

    /**
     * Looks up a requirement by its identity.
     *
     * @param id the requirement identity
     * @return the requirement if present, otherwise {@link Optional#empty()}
     */
    Optional<Requirement> get(RequirementId id);
}
