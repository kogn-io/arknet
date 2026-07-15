package de.hauschel.arknet.req.adapter.kogniordf;

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

import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;

/**
 * Assembles a {@link KognioRdfRequirementRepository} over a concrete kognio-rdf
 * dataset lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the
 * requirements SHACL shapes and ontology axioms onto the classpath. It lets the
 * composition root wire an RDF-persisted requirement repository by handing over just a
 * storage directory, without itself depending on {@code io.kogn.rdf.rdf4j.*} - keeping
 * RDF4J out of arknet-mcp and preserving the port-neutrality of
 * {@link KognioRdfRequirementRepository} and {@link ShaclWriteGate}, which only know
 * technology-neutral kognio-rdf ports.</p>
 */
public final class KognioRdfRequirementRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/requirements-shapes.ttl";
    private static final String AXIOMS_RESOURCE = "/arknet-requirements.ttl";

    private KognioRdfRequirementRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed requirement repository storing its datasets
     * under {@code storageDir}.
     *
     * @param storageDir the directory the embedded RDF store persists into
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository persistent(Path storageDir) {
        return over(persistentLifecycle(storageDir));
    }

    /**
     * Creates the shared persistent, RDF4J-backed kognio-rdf dataset lifecycle stored
     * under {@code storageDir}, returning only the technology-neutral
     * {@link DatasetLifecycle} type.
     *
     * <p>This is the single place that constructs a persistent {@link DatasetLifecycleRdf4j}.
     * The composition root (arknet-mcp) calls it once to obtain <em>one</em> shared lifecycle
     * bean and hands that same instance to every consumer of the store - the requirements and
     * ubiquitous-language repositories (via their {@code over(DatasetLifecycle)} factories) and
     * the generic store report. Sharing a single lifecycle over one storage directory avoids
     * several {@link DatasetLifecycleRdf4j} instances competing for a lock on the same
     * {@code ~/.arknet/rdf} store. Because the return type is the neutral
     * {@link DatasetLifecycle}, arknet-mcp obtains a working store without itself depending on
     * {@code io.kogn.rdf.rdf4j.*} or RDF4J.</p>
     *
     * @param storageDir the directory the embedded RDF store persists into
     * @return the shared, technology-neutral dataset lifecycle
     */
    public static DatasetLifecycle persistentLifecycle(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        return new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
    }

    /**
     * Assembles a requirement repository over an already-created dataset lifecycle,
     * wired with the requirements SHACL write-gate. Used by {@link #persistent(Path)} and
     * directly by tests that supply their own (e.g. in-memory) lifecycle.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository over(DatasetLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        return new KognioRdfRequirementRepository(lifecycle, buildGate());
    }

    /**
     * Builds the requirements write-gate with RDFS reasoning enabled.
     *
     * <p>{@code RequirementShape} targets the abstract {@code arkreq:Requirement}, while
     * instances are typed as the concrete {@code arkreq:FunctionalRequirement} /
     * {@code arkreq:NonFunctionalRequirement}. Without reasoning + the {@code rdfs:subClassOf}
     * axioms merged in via {@code axioms}, the shape never fires (silent pass).</p>
     *
     * @return the assembled requirements SHACL write-gate
     */
    private static ShaclWriteGate buildGate() {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = loadGraph(AXIOMS_RESOURCE);
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, new ValidationOptions(true));
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfRequirementRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
