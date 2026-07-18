package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when
 * {@link de.hauschel.arknet.bc.application.port.out.BoundedContextRepository#create} is called
 * with a {@link BoundedContextCode} that already labels a different bounded context in the
 * targeted workspace.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two bounded contexts both claiming {@code BC-1}. Since
 * {@code dcterms:identifier} is how a human addresses a bounded context, this is an expected,
 * rejectable outcome - not a stack trace.</p>
 */
public class DuplicateBoundedContextCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient BoundedContextCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the code collided in
     * @param code        the bounded-context code that already exists
     */
    public DuplicateBoundedContextCodeException(WorkspaceId workspaceId, BoundedContextCode code) {
        super("bounded context code " + Objects.requireNonNull(code, "code").value()
                + " already exists in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the code collided in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the bounded-context code that already exists */
    public BoundedContextCode code() {
        return code;
    }
}
