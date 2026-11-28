// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Finds a text in every literal of a project, in any field, language and datatype, and names the
 * resource each match belongs to. It sees what a reference-following analysis cannot: a mention
 * with no edge to back it.
 *
 * <p>A match in a child an owned field holds is reported under the resource owning the child; a
 * search never cuts silently - the result carries the total and the limit it was cut at.</p>
 */
public interface FindText {

    /** The most matches one search shows. */
    int LIMIT = 200;

    /**
     * Searches a project.
     *
     * @param projectId the project to read
     * @param query     the substring to find, matched case-insensitively; must not be blank
     * @param type      a prefixed name or the label of a resource class to keep the matches of,
     *                  {@code null} or blank for every resource
     * @return the matches, at most {@link #LIMIT}
     * @throws IllegalArgumentException if the query is blank or the type names no resource class
     */
    TextSearchResult find(ProjectId projectId, String query, String type);
}
