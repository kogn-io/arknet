// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Reads resources of any type by handle and renders each from its shapes and the ontology alone
 * (ADR-60, ADR-69): its fields in the order {@link DescribeResourceType} lists them, its owned
 * children in their order, every edge as handle and label of its target, the edges pointing at it,
 * the statements outside its shapes, and when it was created and last changed.
 *
 * <p>A handle is a business code ({@code FR-1}), a full IRI or a prefixed name over the prefixes of
 * the shipped files. The port takes handles only; which resources a listing reads is the caller's
 * choice.</p>
 */
public interface ReadResources {

    /**
     * Reads the resources the handles name, in the order of the handles, all in one consistent
     * state of the project.
     *
     * @param projectId     the project to read
     * @param handles       the handles; one that names no resource yields a
     *                      {@link ResourceReading.Unresolved} instead of failing the others
     * @param displayLocale the display language, {@code null} or blank for the default; a
     *                      multilingual field shows one value in it, marked when it fell back
     * @param allLanguages  whether a multilingual field shows its value in every language instead
     * @return one reading per handle
     */
    List<ResourceReading> read(ProjectId projectId, List<String> handles, String displayLocale,
            boolean allLanguages);
}
