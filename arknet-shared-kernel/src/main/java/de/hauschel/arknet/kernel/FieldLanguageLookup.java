// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Map;
import java.util.Set;

/**
 * Out-port: which language tags a resource's fields already carry in the store, asked one
 * resource at a time. The one thing a driving MCP adapter cannot answer on its own after a
 * single-language write (kogn-io/arknet#474) - it knows what it wrote and, from
 * {@link ResolvedProject#maintainedLanguages()}, what the project promised, but not what is
 * already there.
 *
 * <p>Implemented once, in the composition root, over the same generic store read path that
 * backs {@code store_overview} and {@code store_check}'s language-gap check - the alternative,
 * a lookup port per bounded context, would be seven ports and seven out-adapter methods for one
 * question that is answered identically everywhere. The direction is the one {@link
 * ProjectResolver} already takes: a port in the shared kernel, implemented in the composition
 * root, consumed by each bounded context's driving adapter, adding no module edge.</p>
 *
 * <p><strong>Field keys are model field names</strong>, which is to say the local names of the
 * RDF predicates behind them ({@code definition}, {@code title}, {@code useCaseGoal}, ...) -
 * the same vocabulary {@code store_check} already reports language gaps under, so the two
 * signals name the same field the same way. A field whose values live on an owned child
 * resource (an acceptance criterion, a use-case step, an ADR consequence) is keyed by the
 * <em>edge</em> that owns it ({@code acceptanceCriterion}, {@code mainStep}, {@code
 * consequence}), pooling the tags of every child hanging off that edge: the caller writes such
 * a list wholesale, so it is the list, not an individual position, that goes stale.</p>
 */
public interface FieldLanguageLookup {

    /**
     * The tags each field of one model resource carries, within that project's own dataset.
     *
     * @param projectId the project whose dataset holds the resource
     * @param code      the resource's business code (e.g. {@code TERM-1}, {@code FR-3})
     * @return field key to the BCP-47 tags that field carries; a field carrying no
     *         language-tagged literal at all is absent rather than mapped to an empty set, and
     *         an unknown code yields an empty map rather than an error - a missing answer costs
     *         a hint, never a write
     */
    Map<String, Set<String>> ofResource(ProjectId projectId, String code);

    /**
     * The tags each field of one project's <em>registry record</em> carries. Its own method
     * rather than a {@link ProjectId} passed to {@link #ofResource}, because that record does
     * not live in the project's dataset at all: the registry is the one bounded context that is
     * not project-scoped and keeps its records in the reserved system dataset, a dataset id
     * {@link ProjectId} refuses to hold by construction ({@link ProjectId#RESERVED_SYSTEM_DATASET}).
     *
     * @param projectLabel the project's label - what the registry record carries as its
     *                     {@code dcterms:identifier}
     * @return field key to the BCP-47 tags that field carries, as in {@link #ofResource}
     */
    Map<String, Set<String>> ofProjectRegistration(String projectLabel);
}
