// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.ontologyModules;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.junit.jupiter.api.Test;

/**
 * No ontology module ships the shapes of another: every {@code sh:targetClass} in a module's shapes
 * files is a class of a namespace the module's own ontology declares, and every target class --
 * also one of a foreign standard such as {@code skos:Concept}, which the glossary of Domain
 * Modelling shapes -- is shaped by exactly one module.
 *
 * <p>A module's own namespaces are read off the {@code owl:Ontology} its ontology files declare
 * ({@code <https://w3id.org/arknet/requirements>} owns {@code https://w3id.org/arknet/requirements#}),
 * and the modules and their files off the indices on the classpath; nothing is listed here.</p>
 */
class ContextShapesTargetOwnNamespaceTest {

    /** Every namespace under this base belongs to arknet; any other is a foreign standard. */
    private static final String ARKNET_BASE = "https://w3id.org/arknet/";

    private static final String SHAPES_SUFFIX = "-shapes.ttl";

    @Test
    void everyModuleShapesOnlyClassesOfItsOwnNamespace() {
        ontologyModules().forEach((index, files) -> {
            Set<String> own = ownNamespaces(files);
            for (IRI target : targetClasses(files)) {
                if (target.stringValue().startsWith(ARKNET_BASE)) {
                    assertTrue(own.contains(target.getNamespace()), "the shapes of the module at " + index
                            + " target " + target + ", a class outside its own namespaces " + own);
                }
            }
        });
    }

    @Test
    void everyTargetClassIsShapedByExactlyOneModule() {
        Map<IRI, Set<URL>> shapedBy = new TreeMap<>(Comparator.comparing(IRI::stringValue));
        ontologyModules().forEach((index, files) -> targetClasses(files)
                .forEach(target -> shapedBy.computeIfAbsent(target,
                        key -> new TreeSet<>(Comparator.comparing(URL::toString))).add(index)));
        assertFalse(shapedBy.isEmpty(), "no shapes file on the classpath targets a class");
        shapedBy.forEach((target, modules) -> assertEquals(1, modules.size(),
                target + " is shaped by more than one ontology module: " + modules));
    }

    private static Set<String> ownNamespaces(final Set<String> files) {
        return files.stream()
                .filter(name -> !name.endsWith(SHAPES_SUFFIX))
                .map(ContextShapesTargetOwnNamespaceTest::load)
                .flatMap(model -> model.filter(null, RDF.TYPE, OWL.ONTOLOGY).subjects().stream())
                .map(ontology -> ontology.stringValue() + "#")
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<IRI> targetClasses(final Set<String> files) {
        return files.stream()
                .filter(name -> name.endsWith(SHAPES_SUFFIX))
                .map(ContextShapesTargetOwnNamespaceTest::load)
                .flatMap(model -> model.filter(null, SHACL.TARGET_CLASS, null).objects().stream())
                .filter(Value::isIRI)
                .map(IRI.class::cast)
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(IRI::stringValue))));
    }

    private static Model load(final String resource) {
        return parse(resource, ContextShapesTargetOwnNamespaceTest.class);
    }
}
