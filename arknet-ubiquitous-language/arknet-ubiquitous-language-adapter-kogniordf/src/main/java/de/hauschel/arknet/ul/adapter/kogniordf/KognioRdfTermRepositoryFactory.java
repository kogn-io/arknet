// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.ul.application.port.out.TermRepository;

/**
 * Assembles a {@link KognioRdfTermRepository} over a concrete kognio-rdf dataset
 * lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the
 * ubiquitous-language SHACL shapes onto the classpath. It lets the composition root wire
 * an RDF-persisted term repository by handing over just a storage directory, without
 * itself depending on {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-mcp and
 * preserving the port-neutrality of {@link KognioRdfTermRepository} and
 * {@link ShaclWriteGate}, which only know technology-neutral kognio-rdf ports.</p>
 */
public final class KognioRdfTermRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/ul-shapes.ttl";

    private KognioRdfTermRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed term repository storing its datasets under
     * {@code storageDir}.
     *
     * @param storageDir the directory the embedded RDF store persists into
     * @return a ready-to-use {@link TermRepository}
     */
    public static TermRepository persistent(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        final DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle);
    }

    /**
     * Assembles a term repository over an already-created dataset lifecycle, wired with
     * the ubiquitous-language SHACL write-gate and the {@link DisplayLocale#DEFAULT} display
     * language. Used by {@link #persistent(Path)} and directly by tests that do not exercise
     * the language fallback.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from
     * @return a ready-to-use {@link TermRepository}
     */
    public static TermRepository over(DatasetLifecycle lifecycle) {
        return over(lifecycle, DisplayLocale.DEFAULT);
    }

    /**
     * Assembles a term repository over an already-created dataset lifecycle, wired with the
     * ubiquitous-language SHACL write-gate and an explicit display language.
     *
     * <p>The {@link DisplayLocale} selects which {@code skos:prefLabel} the read paths surface
     * when a concept carries labels in several languages (issue #80). It is a read-time display
     * concern, so it is constructor-injected here rather than threaded through the
     * {@link TermRepository} port signature - the composition root supplies the process-wide
     * value (see {@code ArknetMcpConfiguration}).</p>
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for label selection
     * @return a ready-to-use {@link TermRepository}
     */
    public static TermRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        ShaclWriteGate gate = buildGate(displayLocale);
        WriteFunnel funnel = new WriteFunnel(lifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        return new KognioRdfTermRepository(lifecycle, displayLocale, funnel);
    }


    /**
     * Builds the ubiquitous-language write-gate.
     *
     * <p>{@code TermShape} targets {@code skos:Concept} directly - the type every term
     * instance already carries - so no RDFS reasoning or ontology axioms are needed (unlike
     * the sibling requirements adapter): an empty {@code axioms} graph and
     * {@link ValidationOptions#defaults()} suffice.</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfTermRepositoryTest} can drive the
     * gate directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * <p>The {@code displayLocale} handed in is the same one the read paths select labels
     * with: a caller that asked to read this glossary in one language is told in that language
     * why a write was refused, whenever the violated shape carries its {@code sh:message} in
     * more than one.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled ubiquitous-language SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = new SimpleRdf().createGraph();
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, ValidationOptions.defaults(),
                displayLocale);
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfTermRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
