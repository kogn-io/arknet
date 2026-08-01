// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * The technical boilerplate shared by every {@code *VocabularyMatchesOntologyTest}: loading a
 * shipped {@code .ttl} off the classpath and building an {@link IRI} from a vocabulary constant.
 * What differs between those tests -- which terms and which declarations a given vocabulary
 * class must match -- stays in each test; only the RDF4J parsing mechanics live here.
 */
public final class OntologyFixtures {

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
}
