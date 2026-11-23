// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The factory lists the shape and ontology files by name; this keeps the list in step with what
 * {@code arknet-ontology} ships, so a new shapes file cannot stay outside the one gate unnoticed.
 */
class KognioRdfResourceRepositoryFactoryTest {

    /** Shipped, but outside generic access: the registry's system dataset, and vocabularies no gate reasons over. */
    private static final Set<String> NOT_FOR_RESOURCES = Set.of("/project-shapes.ttl", "/arknet-project.ttl",
            "/arknet-provenance.ttl", "/arknet-resource.ttl");

    @Test
    void theFactoryListsEveryShippedShapesFile() throws Exception {
        assertEquals(shipped(name -> name.endsWith("-shapes.ttl")),
                new TreeSet<>(KognioRdfResourceRepositoryFactory.SHAPE_RESOURCES));
    }

    @Test
    void theFactoryListsEveryShippedModelOntology() throws Exception {
        assertEquals(shipped(name -> name.startsWith("/arknet-") && !name.endsWith("-shapes.ttl")),
                new TreeSet<>(KognioRdfResourceRepositoryFactory.ONTOLOGY_RESOURCES));
    }

    private static Set<String> shipped(final java.util.function.Predicate<String> kind)
            throws IOException, URISyntaxException {
        URL anchor = KognioRdfResourceRepositoryFactoryTest.class.getResource("/arknet-core.ttl");
        Stream<String> names;
        if ("jar".equals(anchor.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) anchor.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                names = jar.stream().map(entry -> "/" + entry.getName()).toList().stream();
            }
        } else {
            try (Stream<Path> files = Files.list(Path.of(anchor.toURI()).getParent())) {
                names = files.map(file -> "/" + file.getFileName()).toList().stream();
            }
        }
        return names.filter(name -> name.lastIndexOf('/') == 0 && name.endsWith(".ttl"))
                .filter(kind)
                .filter(name -> !NOT_FOR_RESOURCES.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
