package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.req.domain.Requirement;

/**
 * Driving port: list all managed requirements.
 *
 * <p>Backs the MVP tool {@code req_list}.</p>
 */
public interface ListRequirements {

    /**
     * Returns all requirements currently under management.
     *
     * @return all requirements, never {@code null}
     */
    List<Requirement> list();
}
