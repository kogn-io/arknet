// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;

/**
 * Unit test for {@link KognioRdfRequirementRepositoryFactory#buildSchemaSource()} (issue #31):
 * pure classpath Turtle parsing, no {@code DatasetLifecycle} / store involved, so this runs
 * fast and without any RDF4J store setup.
 */
class KognioRdfRequirementRepositoryFactoryTest {

    @Test
    void returnsExactlyTheThreeSchemaTermsInOrderWithTheJavaEnumValues() {
        RequirementSchemaSource source = KognioRdfRequirementRepositoryFactory.buildSchemaSource();

        List<RequirementSchemaTerm> terms = source.schema();

        assertEquals(3, terms.size());

        RequirementSchemaTerm type = terms.get(0);
        assertEquals("RequirementType", type.term());
        assertEquals(List.of("FUNCTIONAL", "NON_FUNCTIONAL"), type.values());
        assertFalse(type.definition().isBlank());

        RequirementSchemaTerm status = terms.get(1);
        assertEquals("RequirementStatus", status.term());
        assertEquals(List.of("PROPOSED", "ACCEPTED"), status.values());
        assertFalse(status.definition().isBlank());

        RequirementSchemaTerm priority = terms.get(2);
        assertEquals("Priority", priority.term());
        assertEquals(List.of("MUST_HAVE", "SHOULD_HAVE", "COULD_HAVE", "WONT_HAVE"), priority.values());
        assertFalse(priority.definition().isBlank());
    }

    /**
     * Regression guard for the design finding this issue hinges on: the Java
     * {@code RequirementStatus} enum is a deliberate MVP subset of the ontology's six
     * {@code arkreq:RequirementStatus} individuals - {@code req_schema} must report only the
     * two values {@code req_add}/{@code req_set_status} actually accept, not the full ontology
     * enumeration.
     */
    @Test
    void requirementStatusValuesAreTheMvpSubsetNotTheFullOntologyEnumeration() {
        RequirementSchemaSource source = KognioRdfRequirementRepositoryFactory.buildSchemaSource();

        RequirementSchemaTerm status = source.schema().stream()
                .filter(t -> t.term().equals("RequirementStatus"))
                .findFirst()
                .orElseThrow();

        assertEquals(2, status.values().size());
    }

    @Test
    void isStableAcrossRepeatedCalls() {
        RequirementSchemaSource source = KognioRdfRequirementRepositoryFactory.buildSchemaSource();

        assertEquals(source.schema(), source.schema());
    }
}
