// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.kogn.rdf.dataset.hosting.DatasetId;

import de.hauschel.arknet.kernel.FieldLanguageLookup;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * The composition root's {@link FieldLanguageLookup}: answers "which languages does this field
 * already carry" out of the same generic store read path that backs {@code store_overview} and
 * {@code store_check}, for every bounded context alike (kogn-io/arknet#474).
 *
 * <p>The mapping this class exists for is the one no other layer may make: a {@link ProjectId}
 * names the project's own dataset, while a <em>project registry record</em> lives in the reserved
 * system dataset, which {@link ProjectId} cannot express. Both are dataset ids to
 * {@link StoreReader}; deciding which one a question means is composition-root work, exactly like
 * {@link de.hauschel.arknet.mcp.RegisteredAnchorProjectResolver}'s routing.</p>
 */
public final class StoreFieldLanguageLookup implements FieldLanguageLookup {

    /** The registry's home - the one dataset no {@link ProjectId} may name. */
    private static final DatasetId SYSTEM_DATASET = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);

    private final StoreReader reader;

    /**
     * @param reader the generic store read path (must not be {@code null})
     */
    public StoreFieldLanguageLookup(final StoreReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public Map<String, Set<String>> ofResource(final ProjectId projectId, final String code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return reader.languageTagsByField(new DatasetId(projectId.value()), code);
    }

    @Override
    public Map<String, Set<String>> ofProjectRegistration(final String projectLabel) {
        Objects.requireNonNull(projectLabel, "projectLabel");
        return reader.languageTagsByField(SYSTEM_DATASET, projectLabel);
    }
}
