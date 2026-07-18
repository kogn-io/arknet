package de.hauschel.arknet.bc.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.persistence.ShaclWriteGate;

/**
 * Assembles a {@link KognioRdfBoundedContextRepository} over a concrete kognio-rdf dataset
 * lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the DDD SHACL
 * shapes onto the classpath. It lets the composition root wire an RDF-persisted bounded-context
 * repository by handing over just a storage directory, without itself depending on
 * {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-mcp and preserving the
 * port-neutrality of {@link KognioRdfBoundedContextRepository} and {@link ShaclWriteGate}, which
 * only know technology-neutral kognio-rdf ports.</p>
 */
public final class KognioRdfBoundedContextRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/arknet-shapes.ttl";

    private KognioRdfBoundedContextRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed bounded-context repository storing its datasets under
     * {@code storageDir}.
     *
     * @param storageDir the directory the embedded RDF store persists into
     * @return a ready-to-use {@link BoundedContextRepository}
     */
    public static BoundedContextRepository persistent(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle);
    }

    /**
     * Assembles a bounded-context repository over an already-created dataset lifecycle, wired with
     * the DDD SHACL write-gate. Used by {@link #persistent(Path)} and directly by tests that
     * supply their own (e.g. in-memory) lifecycle.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from
     * @return a ready-to-use {@link BoundedContextRepository}
     */
    public static BoundedContextRepository over(DatasetLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        return new KognioRdfBoundedContextRepository(lifecycle, buildGate());
    }

    /**
     * Builds the bounded-context write-gate.
     *
     * <p>{@code shapes:BoundedContextShape} targets {@code arknet:BoundedContext} directly - the
     * type every bounded-context instance already carries - so no RDFS reasoning or ontology
     * axioms are needed (unlike the requirements adapter, whose shape targets the abstract
     * {@code arkreq:Requirement} superclass): an empty {@code axioms} graph and
     * {@link ValidationOptions#defaults()} suffice. The shapes graph loaded is the whole DDD
     * shapes file; only {@code BoundedContextShape} targets a bounded-context subject, so the
     * sibling shapes ({@code AggregateShape}, ...) never fire against a candidate this adapter
     * writes.</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfBoundedContextRepositoryTest} can drive
     * the gate directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * @return the assembled DDD SHACL write-gate
     */
    static ShaclWriteGate buildGate() {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = new SimpleRdf().createGraph();
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, ValidationOptions.defaults());
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfBoundedContextRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
