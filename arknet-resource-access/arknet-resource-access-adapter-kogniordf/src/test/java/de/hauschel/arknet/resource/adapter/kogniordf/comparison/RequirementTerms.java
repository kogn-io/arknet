// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * The requirement terms {@code ArkreqVocabulary} does not carry as constants (rationale, priority,
 * status, quality category and the individuals of the two value sets), derived from the namespace
 * of a constant it does carry, so an edit of that namespace in kogn-io/arknet#745 reaches them too.
 * A rename of a local name must be repeated here until the vocabulary holds these terms.
 */
final class RequirementTerms {

    private static final String NAMESPACE = ArkreqVocabulary.POSITION
            .substring(0, ArkreqVocabulary.POSITION.length() - "position".length());

    static final String RATIONALE = NAMESPACE + "rationale";
    static final String PRIORITY = NAMESPACE + "priority";
    static final String STATUS = NAMESPACE + "status";
    static final String QUALITY_CATEGORY = NAMESPACE + "qualityCategory";

    static final String MUST_HAVE = NAMESPACE + "MustHave";
    static final String SHOULD_HAVE = NAMESPACE + "ShouldHave";
    static final String COULD_HAVE = NAMESPACE + "CouldHave";
    static final String WONT_HAVE = NAMESPACE + "WontHave";

    static final String PROPOSED = NAMESPACE + "Proposed";
    static final String ACCEPTED = NAMESPACE + "Accepted";
    static final String IMPLEMENTED = NAMESPACE + "Implemented";

    private RequirementTerms() {
    }
}
