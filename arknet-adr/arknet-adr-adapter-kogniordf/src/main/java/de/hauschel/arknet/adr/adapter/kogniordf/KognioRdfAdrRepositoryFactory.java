// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Assembles a {@link KognioRdfAdrRepository} over a concrete kognio-rdf dataset lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the architecture
 * SHACL shapes off the classpath. It lets the composition root wire an RDF-persisted ADR repository
 * by handing over just a storage directory, without itself depending on {@code io.kogn.rdf.rdf4j.*}
 * - keeping RDF4J out of arknet-mcp and preserving the port-neutrality of
 * {@link KognioRdfAdrRepository} and {@link ShaclWriteGate}, which only know technology-neutral
 * kognio-rdf ports.</p>
 */
public final class KognioRdfAdrRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/architecture-shapes.ttl";

    private KognioRdfAdrRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed ADR repository storing its datasets under
     * {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link AdrRepository}
     */
    public static AdrRepository persistent(Path storageDir, DisplayLocale displayLocale) {
        Objects.requireNonNull(storageDir, "storageDir");
        DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle, displayLocale);
    }

    /**
     * Assembles an ADR repository over an already-created dataset lifecycle, wired with the
     * architecture SHACL write-gate and an explicit display language. Used by
     * {@link #persistent(Path, DisplayLocale)} and directly by tests that supply their own (e.g.
     * in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link AdrRepository}
     */
    public static AdrRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        WriteFunnel funnel = new WriteFunnel(lifecycle, buildGate(displayLocale),
                WriteFunnel.DEFAULT_WRITE_CONFLICT);
        return new KognioRdfAdrRepository(lifecycle, funnel);
    }

    /**
     * Builds the ADR write-gate.
     *
     * <p>{@code ashapes:ADRShape} targets {@code arkarch:ArchitectureDecisionRecord} directly - the
     * type every decision already carries - so no RDFS reasoning or ontology axioms are needed
     * (unlike the requirements adapter, whose shape targets the abstract {@code arkreq:Requirement}
     * superclass): an empty {@code axioms} graph and {@link ValidationOptions#defaults()} suffice,
     * exactly as for the bounded-context and glossary gates. The shapes graph loaded is the whole
     * active architecture shapes file, which today contains nothing but the ADR shapes - the
     * remaining ISO-42010 shapes have no consumer and stay parked, so unlike the use-cases factory
     * this one needs no shape filtering either.</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfAdrRepositoryTest} can drive the gate
     * directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * <p>The {@code displayLocale} handed in is the same one the composition root configures
     * process-wide: a caller gets told why a write was refused in the same language regardless of
     * which bounded context rejected it.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled architecture SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = new SimpleRdf().createGraph();
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, ValidationOptions.defaults(),
                displayLocale);
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfAdrRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
