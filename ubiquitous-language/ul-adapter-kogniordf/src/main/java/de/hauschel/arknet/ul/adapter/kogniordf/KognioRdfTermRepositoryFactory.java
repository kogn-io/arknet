package de.hauschel.arknet.ul.adapter.kogniordf;

import java.nio.file.Path;
import java.util.Objects;

import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;

import de.hauschel.arknet.ul.application.port.out.TermRepository;

/**
 * Assembles a {@link KognioRdfTermRepository} over a concrete kognio-rdf dataset
 * lifecycle.
 *
 * <p>This factory is the single place that names the RDF4J-backed lifecycle
 * ({@link DatasetLifecycleRdf4j}). It lets the composition root wire an RDF-persisted
 * term repository by handing over just a storage directory, without itself depending
 * on {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-mcp and preserving the
 * port-neutrality of {@link KognioRdfTermRepository}, which only knows the
 * technology-neutral {@link DatasetLifecycle} port.</p>
 */
public final class KognioRdfTermRepositoryFactory {

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
        return new KognioRdfTermRepository(lifecycle);
    }
}
