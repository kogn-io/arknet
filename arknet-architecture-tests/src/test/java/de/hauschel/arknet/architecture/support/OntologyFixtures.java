// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

    /**
     * A resource {@code arknet-ontology} certainly ships, used to locate the directory or jar the
     * others sit in.
     */
    private static final String ANCHOR_RESOURCE = "/arknet-core.ttl";

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
     * Every {@code .ttl} at the root of {@code arknet-ontology} -- the live modules and their
     * shapes, with the {@code parked/} subdirectory left out by construction.
     *
     * <p>Enumerated off the classpath rather than listed in a test: a hand-kept list would be the
     * very kind of forgotten line the tests using this exist to catch. {@code arknet-ontology}
     * resolves to a jar in an installed build and to a {@code target/classes} directory inside the
     * reactor, so both layouts are handled.</p>
     *
     * @return absolute classpath names, e.g. {@code /arknet-core.ttl}
     */
    public static Set<String> shippedOntologyResources() {
        URL anchor = OntologyFixtures.class.getResource(ANCHOR_RESOURCE);
        if (anchor == null) {
            throw new IllegalStateException("missing classpath resource " + ANCHOR_RESOURCE);
        }
        try {
            return "jar".equals(anchor.getProtocol()) ? jarEntries(anchor) : directoryEntries(anchor);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("failed to enumerate the shipped ontologies", e);
        }
    }

    private static Set<String> jarEntries(URL anchor) throws IOException {
        String jarPath = anchor.getPath().substring("file:".length(), anchor.getPath().indexOf('!'));
        Set<String> resources = new HashSet<>();
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

    private static Set<String> directoryEntries(URL anchor) throws URISyntaxException {
        File[] files = new File(anchor.toURI()).getParentFile().listFiles();
        Set<String> resources = new HashSet<>();
        for (File file : files == null ? new File[0] : files) {
            if (file.isFile() && file.getName().endsWith(".ttl")) {
                resources.add("/" + file.getName());
            }
        }
        return resources;
    }
}
