package de.hauschel.arknet.req.domain;

/**
 * Kind of a {@link Requirement} in the sense of classic requirements engineering.
 *
 * <ul>
 *   <li>{@link #FUNCTIONAL} - what the system must do (behaviour, features).</li>
 *   <li>{@link #NON_FUNCTIONAL} - qualities/constraints (performance, security, ...).</li>
 * </ul>
 */
public enum RequirementType {

    /** What the system must do; identities are prefixed {@code FR-}. */
    FUNCTIONAL("FR"),

    /** Qualities/constraints; identities are prefixed {@code NFR-}. */
    NON_FUNCTIONAL("NFR");

    private final String idPrefix;

    RequirementType(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    /**
     * The identity prefix used for this type, e.g. {@code FR} for functional
     * requirements. Combined with a running number ({@code FR-1}, {@code NFR-7})
     * by the application layer.
     *
     * @return the non-blank identity prefix
     */
    public String idPrefix() {
        return idPrefix;
    }
}
