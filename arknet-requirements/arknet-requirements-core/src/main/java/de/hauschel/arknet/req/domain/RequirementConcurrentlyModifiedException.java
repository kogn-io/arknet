package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when a read-modify-write round trip (e.g. {@code req_link_term}, {@code
 * req_set_status}) keeps losing the optimistic-concurrency race against other writers of the
 * same requirement (see {@link
 * de.hauschel.arknet.req.application.port.out.RequirementRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same requirement. Distinct from {@link
 * RequirementNotFoundException} - the requirement exists throughout, it is just never observed
 * to still match the caller's stale read for long enough to commit.</p>
 */
public class RequirementConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient RequirementCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the requirement lives in
     * @param code        the requirement code whose update kept losing the race
     */
    public RequirementConcurrentlyModifiedException(WorkspaceId workspaceId, RequirementCode code) {
        super("requirement " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the requirement lives in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the requirement code whose update kept losing the race */
    public RequirementCode requirementCode() {
        return code;
    }
}
