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
    FUNCTIONAL,
    NON_FUNCTIONAL
}
