// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.SailConflictException;

import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

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
    private static final String REQUIREMENTS_NS = "https://w3id.org/arknet/requirements#";

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
        return new KognioRdfRequirementRepository(lifecycle, buildGate(),
                KognioRdfRequirementRepositoryFactory::isWriteConflict);
    }

    /**
     * Recognises the RDF4J-backed store's commit-time signal for a lost {@code SERIALIZABLE}
     * transaction conflict (issue #144, kogn-io/rdf-core#18): a {@link RepositoryException} whose
     * cause chain carries a {@link SailConflictException}. Like {@link #buildGate()}, this method
     * stays the only place in this package naming those RDF4J types (ArchUnit rule 2) - the
     * method reference passed to {@link KognioRdfRequirementRepository} above hands it over as a
     * technology-neutral {@code Predicate} that references no RDF4J type itself.
     *
     * <p>Package-private (not {@code private}) so a concurrency test can wire it directly, the
     * same reason {@link #buildGate()} is.</p>
     */
    static boolean isWriteConflict(RuntimeException candidate) {
        if (!(candidate instanceof RepositoryException)) {
            return false;
        }
        for (Throwable cause = candidate.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SailConflictException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the requirements write-gate with RDFS reasoning enabled.
     *
     * <p>{@code RequirementShape} targets the abstract {@code arkreq:Requirement}, while
     * instances are typed as the concrete {@code arkreq:FunctionalRequirement} /
     * {@code arkreq:NonFunctionalRequirement}. Without reasoning + the {@code rdfs:subClassOf}
     * axioms merged in via {@code axioms}, the shape never fires (silent pass).</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfRequirementRepositoryTest} can drive
     * the gate directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * @return the assembled requirements SHACL write-gate
     */
    static ShaclWriteGate buildGate() {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = loadGraph(AXIOMS_RESOURCE);
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, new ValidationOptions(true));
    }

    /**
     * Builds the {@code arkreq:} requirement vocabulary as data (issue #31): one
     * {@link RequirementSchemaTerm} each for {@code RequirementType}, {@code RequirementStatus}
     * and {@code Priority}, carrying its ontology-sourced ({@code arknet-requirements.ttl})
     * class-level {@code rdfs:comment}{@code @de} as definition and the exact values the
     * corresponding Java domain enum accepts - deliberately not the ontology's own (richer)
     * {@code sh:in} SHACL enumeration, so a caller is never told a value
     * {@code req_add}/{@code req_set_status} would actually reject (see
     * {@link RequirementSchemaTerm}). There is no standalone {@code arkreq:RequirementType}
     * class in the ontology, so the {@code RequirementType} term reuses the base
     * {@code arkreq:Requirement} class's comment.
     *
     * <p>Parses the ontology once and returns an already-built, immutable list captured by a
     * lambda that itself references no RDF4J type - this method stays the only place in the
     * package naming RDF4J, exactly like {@link #buildGate()}.</p>
     *
     * @return a ready-to-use {@link RequirementSchemaSource}
     */
    public static RequirementSchemaSource buildSchemaSource() {
        Model model = parseModel(AXIOMS_RESOURCE);
        List<RequirementSchemaTerm> terms = List.of(
                schemaTerm(model, "RequirementType", "Requirement", RequirementType.values()),
                schemaTerm(model, "RequirementStatus", "RequirementStatus", RequirementStatus.values()),
                schemaTerm(model, "Priority", "Priority", Priority.values()));
        return () -> terms;
    }

    private static <E extends Enum<E>> RequirementSchemaTerm schemaTerm(
            Model model, String term, String ontologyClassLocalName, E[] values) {
        String definition = classComment(model, ontologyClassLocalName);
        List<String> valueNames = Arrays.stream(values).map(Enum::name).toList();
        return new RequirementSchemaTerm(term, definition, valueNames);
    }

    /** Looks up the (single, {@code @de}) class-level {@code rdfs:comment} of {@code arkreq:<localName>}. */
    private static String classComment(Model model, String localName) {
        IRI subject = SimpleValueFactory.getInstance().createIRI(REQUIREMENTS_NS, localName);
        return model.filter(subject, RDFS.COMMENT, null).objects().stream()
                .filter(Literal.class::isInstance)
                .map(Literal.class::cast)
                .filter(literal -> literal.getLanguage().map("de"::equals).orElse(false))
                .map(Literal::stringValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing @de rdfs:comment for " + subject));
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        return new RDF4JGraph(parseModel(classpathResource));
    }

    private static Model parseModel(String classpathResource) {
        try (InputStream in = KognioRdfRequirementRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            return Rio.parse(in, "", RDFFormat.TURTLE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
