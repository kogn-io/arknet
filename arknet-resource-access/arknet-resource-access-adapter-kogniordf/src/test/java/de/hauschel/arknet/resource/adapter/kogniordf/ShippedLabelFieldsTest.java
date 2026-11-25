// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.resource.domain.ResourceSchema;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * The shapes the four model contexts ship carry the label field rule datum: every resource type
 * with a text field names exactly one label path, the one without a text field names none.
 */
class ShippedLabelFieldsTest {

    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String NAME = "https://w3id.org/arknet/core#name";

    @Test
    void everyShippedResourceTypeNamesItsLabelPathOrNone() {
        ResourceSchema schema = KognioRdfResourceRepositoryFactory.schema();
        SimpleRdf rdf = new SimpleRdf();

        Map<String, String> labels = new TreeMap<>();
        for (String type : schema.resourceTypes()) {
            Optional<IRI> label = schema.labelField(Set.of(rdf.createIRI(type)));
            labels.put(type, label.map(IRI::getIRIString).orElse("none"));
        }

        assertEquals(Map.ofEntries(
                Map.entry("https://w3id.org/arknet/requirements#FunctionalRequirement", TITLE),
                Map.entry("https://w3id.org/arknet/requirements#NonFunctionalRequirement", TITLE),
                Map.entry("https://w3id.org/arknet/requirements#BusinessConstraint", TITLE),
                Map.entry("https://w3id.org/arknet/requirements#TechnicalConstraint", TITLE),
                Map.entry("https://w3id.org/arknet/requirements#RegulatoryConstraint", TITLE),
                Map.entry("https://w3id.org/arknet/requirements#UseCase", TITLE),
                Map.entry("http://www.w3.org/2004/02/skos/core#Concept",
                        "http://www.w3.org/2004/02/skos/core#prefLabel"),
                Map.entry("https://w3id.org/arknet/architecture#ArchitectureDecisionRecord", NAME),
                Map.entry("https://w3id.org/arknet/process#HumanActor", NAME),
                Map.entry("https://w3id.org/arknet/process#SystemActor", NAME),
                Map.entry("https://w3id.org/arknet/process#LegalActor", NAME),
                Map.entry("https://w3id.org/arknet/process#GroupActor", NAME),
                Map.entry("https://w3id.org/arknet/process#Role", NAME),
                Map.entry("https://w3id.org/arknet/ddd#BoundedContext", NAME),
                Map.entry("https://w3id.org/arknet/ddd#ContextRelationship", "none")), labels);
    }
}
