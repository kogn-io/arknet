package io.kognio.arknet.core;

import org.eclipse.rdf4j.model.vocabulary.RDF4J;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclSail;
import org.eclipse.rdf4j.sail.shacl.ShaclSailValidationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class ModelLoader {

    private static final String ONTOLOGY_RESOURCE = "/arknet-ontology.ttl";
    private static final String SHAPES_RESOURCE = "/arknet-shapes.ttl";

    public Repository loadModel(Path modelPath) throws IOException {
        var repo = new SailRepository(new MemoryStore());
        repo.init();
        try (var conn = repo.getConnection()) {
            conn.begin();
            loadOntology(conn);
            conn.add(modelPath.toFile(), "", RDFFormat.TURTLE);
            conn.commit();
        }
        return repo;
    }

    public ValidationReport validateModel(Path modelPath) throws IOException {
        var shaclSail = new ShaclSail(new MemoryStore());
        var repo = new SailRepository(shaclSail);
        repo.init();

        try (var conn = repo.getConnection()) {
            conn.begin();
            try (InputStream shapes = getClass().getResourceAsStream(SHAPES_RESOURCE)) {
                if (shapes == null) throw new IOException("SHACL shapes not found on classpath");
                conn.add(shapes, "", RDFFormat.TURTLE, RDF4J.SHACL_SHAPE_GRAPH);
            }
            conn.commit();

            conn.begin();
            loadOntology(conn);
            conn.add(modelPath.toFile(), "", RDFFormat.TURTLE);
            try {
                conn.commit();
                return ValidationReport.valid();
            } catch (RepositoryException e) {
                Throwable cause = e.getCause();
                while (cause != null) {
                    if (cause instanceof ShaclSailValidationException sve) {
                        return ValidationReport.fromShaclReport(sve.validationReportAsModel());
                    }
                    cause = cause.getCause();
                }
                throw e;
            }
        } finally {
            repo.shutDown();
        }
    }

    private void loadOntology(RepositoryConnection conn) throws IOException {
        try (InputStream ontology = getClass().getResourceAsStream(ONTOLOGY_RESOURCE)) {
            if (ontology == null) throw new IOException("Ontology not found on classpath");
            conn.add(ontology, "", RDFFormat.TURTLE);
        }
    }
}
