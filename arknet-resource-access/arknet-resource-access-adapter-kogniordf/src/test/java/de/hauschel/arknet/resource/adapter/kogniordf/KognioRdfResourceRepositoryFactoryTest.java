// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The factory finds the shape and ontology files through the index every ontology module ships;
 * this keeps the indices in step with what the modules on the classpath actually ship, so a new
 * shapes file cannot stay outside the one gate unnoticed.
 */
class KognioRdfResourceRepositoryFactoryTest {

    @Test
    void theFactoryFindsEveryShippedShapesFile() throws Exception {
        Set<String> shipped = shipped(name -> name.endsWith(KognioRdfResourceRepositoryFactory.SHAPES_SUFFIX));
        assertFalse(shipped.isEmpty(), "no ontology module on the test classpath ships a shapes file");
        assertEquals(shipped, new TreeSet<>(KognioRdfResourceRepositoryFactory.shapeResources()));
    }

    @Test
    void theFactoryFindsEveryShippedModelOntology() throws Exception {
        assertEquals(shipped(name -> !name.endsWith(KognioRdfResourceRepositoryFactory.SHAPES_SUFFIX)
                && !KognioRdfResourceRepositoryFactory.RESOURCE_ONTOLOGY.equals(name)),
                new TreeSet<>(KognioRdfResourceRepositoryFactory.ontologyResources()));
    }

    /**
     * The opt-out list names files that are still shipped: a renamed or moved system file would
     * otherwise drop out of the list unnoticed and enter the one gate under its new name.
     */
    @Test
    void everyFileLeftOutOfGenericAccessIsStillIndexed() {
        Set<String> indexed = KognioRdfResourceRepositoryFactory.indexedFiles();
        KognioRdfResourceRepositoryFactory.OUTSIDE_GENERIC_ACCESS.forEach(name -> assertTrue(indexed.contains(name),
                name + " is left out of generic access by name, but no ontology index lists it any more"));
        assertTrue(indexed.contains(KognioRdfResourceRepositoryFactory.RESOURCE_ONTOLOGY),
                KognioRdfResourceRepositoryFactory.RESOURCE_ONTOLOGY + " is named by the factory, but no index lists it");
    }

    /**
     * The root {@code .ttl} files of every module that ships an index, read off the jar or the
     * {@code target/classes} directory the index sits in -- not off the index itself.
     */
    private static Set<String> shipped(final Predicate<String> kind) throws IOException, URISyntaxException {
        Set<String> names = new TreeSet<>();
        for (URL index : Collections.list(KognioRdfResourceRepositoryFactoryTest.class.getClassLoader()
                .getResources(KognioRdfResourceRepositoryFactory.ONTOLOGY_INDEX))) {
            names.addAll(rootTurtleFiles(index));
        }
        return names.stream()
                .filter(kind)
                .filter(name -> !KognioRdfResourceRepositoryFactory.OUTSIDE_GENERIC_ACCESS.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> rootTurtleFiles(final URL index) throws IOException, URISyntaxException {
        Stream<String> names;
        if ("jar".equals(index.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) index.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                names = jar.stream().map(entry -> "/" + entry.getName()).toList().stream();
            }
        } else {
            Path root = Path.of(index.toURI()).getParent().getParent().getParent();
            try (Stream<Path> files = Files.list(root)) {
                names = files.map(file -> "/" + file.getFileName()).toList().stream();
            }
        }
        return names.filter(name -> name.lastIndexOf('/') == 0 && name.endsWith(".ttl"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
