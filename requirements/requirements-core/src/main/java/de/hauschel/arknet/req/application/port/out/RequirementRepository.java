package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve requirements"), not after
 * any technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 */
public interface RequirementRepository {

    /**
     * Persists the given requirement, inserting or replacing by identity.
     *
     * @param requirement the requirement to store
     */
    void save(Requirement requirement);

    /**
     * Finds a requirement by its identity.
     *
     * @param id the requirement identity
     * @return the requirement if present, otherwise {@link Optional#empty()}
     */
    Optional<Requirement> findById(RequirementId id);

    /**
     * Returns all stored requirements.
     *
     * @return all requirements, never {@code null}
     */
    List<Requirement> findAll();
}
