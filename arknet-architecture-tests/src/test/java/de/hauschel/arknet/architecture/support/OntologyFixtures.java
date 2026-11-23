// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * The technical boilerplate shared by every {@code *VocabularyMatchesOntologyTest}: loading a
 * shipped {@code .ttl} off the classpath, enumerating which ones there are, and building an
 * {@link IRI} from a vocabulary constant. What differs between those tests -- which terms and
 * which declarations a given vocabulary class must match -- stays in each test; only the RDF4J
 * parsing and classpath mechanics live here.
 */
public final class OntologyFixtures {

    /** The classpath name under which every ontology module lists its live files, one per line. */
    public static final String ONTOLOGY_INDEX = "META-INF/arknet/ontology.index";

    private OntologyFixtures() {
    }

    public static IRI iri(final String value) {
        return Values.iri(value);
    }

    public static Model parse(final String resource, final Class<?> test) {
        try (InputStream in = test.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, "missing classpath resource " + resource);
            return Rio.parse(in, "", RDFFormat.TURTLE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + resource, e);
        }
    }

    /**
     * Every live {@code .ttl} the ontology modules on the classpath ship -- {@code arknet-ontology}
     * and the ontology module of each bounded context --, as their indices name them. The
     * {@code parked/} subdirectories are never indexed.
     *
     * <p>Read off the indices rather than listed in a test: a hand-kept list would be the very kind
     * of forgotten line the tests using this exist to catch. That an index names exactly what its
     * module ships is {@code OntologyIndexListsEveryShippedFileTest}'s concern.</p>
     *
     * @return absolute classpath names, e.g. {@code /arknet-core.ttl}
     */
    public static Set<String> shippedOntologyResources() {
        Set<String> resources = new TreeSet<>();
        ontologyModules().values().forEach(resources::addAll);
        return resources;
    }

    /**
     * Every ontology module on the classpath, keyed by the location of its index, with the files the
     * index names.
     *
     * @return the indexed files per index location, in a stable order
     */
    public static Map<URL, Set<String>> ontologyModules() {
        Map<URL, Set<String>> modules = new TreeMap<>(Comparator.comparing(URL::toString));
        try {
            for (URL index : Collections.list(OntologyFixtures.class.getClassLoader().getResources(ONTOLOGY_INDEX))) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
                    modules.put(index, reader.lines()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .collect(Collectors.toCollection(TreeSet::new)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + ONTOLOGY_INDEX, e);
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("no " + ONTOLOGY_INDEX + " on the classpath");
        }
        return modules;
    }

    /**
     * The {@code .ttl} files at the root of the module an index belongs to, read off its jar or its
     * {@code target/classes} directory -- what the module actually ships, independent of the index.
     *
     * @param index the location of a module's index
     * @return absolute classpath names
     */
    public static Set<String> rootTurtleFiles(final URL index) {
        try {
            return "jar".equals(index.getProtocol()) ? jarEntries(index) : directoryEntries(index);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("failed to enumerate the files next to " + index, e);
        }
    }

    private static Set<String> jarEntries(final URL index) throws IOException {
        String jarPath = index.getPath().substring("file:".length(), index.getPath().indexOf('!'));
        Set<String> resources = new TreeSet<>();
        try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, StandardCharsets.UTF_8))) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".ttl") && name.indexOf('/') < 0) {
                    resources.add("/" + name);
                }
            }
        }
        return resources;
    }

    private static Set<String> directoryEntries(final URL index) throws URISyntaxException {
        File root = new File(index.toURI()).getParentFile().getParentFile().getParentFile();
        File[] files = root.listFiles();
        Set<String> resources = new TreeSet<>();
        for (File file : files == null ? new File[0] : files) {
            if (file.isFile() && file.getName().endsWith(".ttl")) {
                resources.add("/" + file.getName());
            }
        }
        return resources;
    }
}
