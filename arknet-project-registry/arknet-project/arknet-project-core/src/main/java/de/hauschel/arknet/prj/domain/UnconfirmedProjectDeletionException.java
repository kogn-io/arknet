// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thrown when a project that still holds model resources is to be deleted without an explicit
 * confirmation.
 *
 * <p>The other {@code *_delete} tools refuse while something still references the resource; a
 * project has no such outside reference - it is the outermost container - so the guard here is a
 * different one: the caller has to say, in a parameter of its own, that losing the model is
 * intended. The message states what would be lost, resource type by resource type, before
 * anything is removed, so that confirmation is given knowing the size of it.</p>
 */
public class UnconfirmedProjectDeletionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Project project;
    private final transient Map<String, Long> resourceCounts;

    /**
     * Creates the exception.
     *
     * @param project        the project that was asked to be deleted
     * @param resourceCounts how many model resources of which type the project's dataset holds,
     *                       never empty
     */
    public UnconfirmedProjectDeletionException(Project project, Map<String, Long> resourceCounts) {
        super(message(Objects.requireNonNull(project, "project"),
                Objects.requireNonNull(resourceCounts, "resourceCounts")));
        this.project = project;
        this.resourceCounts = Collections.unmodifiableMap(new LinkedHashMap<>(resourceCounts));
    }

    private static String message(Project project, Map<String, Long> resourceCounts) {
        String counts = resourceCounts.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", "));
        return "Project '" + project.label() + "' (id: " + project.id().value() + ") still holds model "
                + "resources that would be lost with it: " + counts + ". Nothing was deleted. Call "
                + "project_delete again with confirm=true to delete the project together with them.";
    }

    /** @return the project that was asked to be deleted */
    public Project project() {
        return project;
    }

    /** @return how many model resources of which type the project's dataset holds, in the order given */
    public Map<String, Long> resourceCounts() {
        return resourceCounts;
    }
}
