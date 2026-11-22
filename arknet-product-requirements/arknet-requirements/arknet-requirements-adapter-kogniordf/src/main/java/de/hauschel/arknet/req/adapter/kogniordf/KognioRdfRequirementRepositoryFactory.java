// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.hosting.DatasetStoreLockedException;
import io.kogn.rdf.rdf4j.dataset.GraphImportRdf4j;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
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
 * ({@link ShaclValidationRdf4j}) and the Turtle import ({@link GraphImportRdf4j}) used to load the
 * requirements SHACL shapes and ontology axioms onto the classpath. It lets the
 * composition root wire an RDF-persisted requirement repository by handing over just a
 * storage directory, without itself depending on {@code io.kogn.rdf.rdf4j.*} - keeping
 * RDF4J out of arknet-app and preserving the port-neutrality of
 * {@link KognioRdfRequirementRepository} and {@link ShaclWriteGate}, which only know
 * technology-neutral kognio-rdf ports.</p>
 */
public final class KognioRdfRequirementRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/requirements-shapes.ttl";
    private static final String AXIOMS_RESOURCE = "/arknet-requirements.ttl";
    private static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    private static final String REQUIREMENTS_NS = "https://w3id.org/arknet/requirements#";

    /**
     * Recognises a {@link DatasetLifecycle#acquire} failure caused by a second instance
     * already holding the storage directory's file lock: the kognio-rdf hosting port reports
     * it as {@link DatasetStoreLockedException}, so this one type is the complete signal - no
     * cause chain to walk.
     * This is the predicate arknet-app's shared {@code DatasetLifecycle} bean wants; it is
     * offered as a default rather than hard-wired, because a different store behind
     * {@link DatasetLifecycle} may fail its lock conflicts differently, and this
     * factory - the one place allowed to name RDF4J - would then be the wrong place to
     * encode that for it.
     */
    public static final Predicate<RuntimeException> DEFAULT_LOCK_CONFLICT =
            DatasetStoreLockedException.class::isInstance;

    private KognioRdfRequirementRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed requirement repository storing its datasets
     * under {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository persistent(Path storageDir, DisplayLocale displayLocale) {
        return over(persistentLifecycle(storageDir), displayLocale);
    }

    /**
     * Creates the shared persistent, RDF4J-backed kognio-rdf dataset lifecycle stored
     * under {@code storageDir}, returning only the technology-neutral
     * {@link DatasetLifecycle} type.
     *
     * <p>This is the single place that constructs a persistent {@link DatasetLifecycleRdf4j}.
     * The composition root (arknet-app) calls it once to obtain <em>one</em> shared lifecycle
     * bean and hands that same instance to every consumer of the store - the requirements and
     * ubiquitous-language repositories (via their {@code over(DatasetLifecycle)} factories) and
     * the generic store report. Sharing a single lifecycle over one storage directory avoids
     * several {@link DatasetLifecycleRdf4j} instances competing for a lock on the same
     * {@code ~/.arknet/rdf} store. Because the return type is the neutral
     * {@link DatasetLifecycle}, arknet-app obtains a working store without itself depending on
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
     * Assembles a requirement repository over an already-created dataset lifecycle, wired with
     * the requirements SHACL write-gate and an explicit display language. Used by
     * {@link #persistent(Path, DisplayLocale)} and directly by tests that supply their own
     * (e.g. in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        return over(lifecycle, new UuidResourceIdFactory(), displayLocale);
    }

    /**
     * {@link #over(DatasetLifecycle, DisplayLocale)}, with an explicit {@link ResourceIdFactory}
     * (issue #266) - the seam a test that needs deterministic/inspectable acceptance-criterion IRIs
     * uses instead of the default {@link UuidResourceIdFactory}.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from
     * @param resourceIdFactory mints the opaque IRI of each derived acceptance-criterion resource;
     *                          the same kernel-owned scheme the composition root uses everywhere
     *                          else (e.g. {@code KognioRdfUseCaseRepositoryFactory})
     * @param displayLocale     the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository over(
            DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        Objects.requireNonNull(displayLocale, "displayLocale");
        VersionedWriteFunnel funnel = buildFunnel(lifecycle, displayLocale);
        return over(lifecycle, resourceIdFactory, displayLocale, funnel);
    }

    /**
     * Assembles a requirement repository over an already-built {@link VersionedWriteFunnel} - the seam
     * the composition root uses to share one funnel instance between this repository and
     * {@code KognioRdfConstraintRepositoryFactory#over} (issue #223), rather than each building
     * its own, functionally identical one (see {@link #buildFunnel} for why sharing is the
     * point).
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from
     * @param resourceIdFactory mints the opaque IRI of each derived acceptance-criterion resource
     *                          (issue #266)
     * @param displayLocale     the display-language preference for SHACL violation messages
     * @param funnel            the already-built write funnel to run every write through
     * @return a ready-to-use {@link RequirementRepository}
     */
    public static RequirementRepository over(DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory,
            DisplayLocale displayLocale, VersionedWriteFunnel funnel) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        Objects.requireNonNull(displayLocale, "displayLocale");
        Objects.requireNonNull(funnel, "funnel");
        return new KognioRdfRequirementRepository(lifecycle, resourceIdFactory, displayLocale, funnel);
    }

    /**
     * Builds the shared {@link VersionedWriteFunnel} every write path of the requirements hexagon runs
     * through - both {@link KognioRdfRequirementRepository} and, over the same instance,
     * {@code KognioRdfConstraintRepository} (issue #223): {@code Constraint} shares the
     * requirements SHACL shapes and ontology axioms (both already live in
     * {@code requirements-shapes.ttl}/{@code arknet-requirements.ttl}), so sharing one gate and
     * one funnel instance is the point - not building a second, functionally identical one.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return the assembled write funnel
     */
    public static VersionedWriteFunnel buildFunnel(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        ShaclWriteGate gate = buildGate(displayLocale);
        return new VersionedWriteFunnel(lifecycle, gate, ContentGraph.GRAPH_IRI,
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT);
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
     * <p>The {@code displayLocale} handed in is the same one the composition root configures
     * process-wide: a caller gets told why a write was refused in the same language regardless of
     * which bounded context rejected it, whenever the violated shape carries its {@code sh:message}
     * in more than one.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled requirements SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = loadGraph(AXIOMS_RESOURCE);
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, new ValidationOptions(true),
                displayLocale);
    }

    /**
     * Builds the {@code arkreq:} requirement vocabulary as data: one
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
     * package naming RDF4J, exactly like {@link #buildGate(DisplayLocale)}.</p>
     *
     * @return a ready-to-use {@link RequirementSchemaSource}
     */
    public static RequirementSchemaSource buildSchemaSource() {
        ReadableGraph model = loadGraph(AXIOMS_RESOURCE);
        List<RequirementSchemaTerm> terms = List.of(
                schemaTerm(model, "RequirementType", "Requirement", RequirementType.values()),
                schemaTerm(model, "RequirementStatus", "RequirementStatus", RequirementStatus.values()),
                schemaTerm(model, "Priority", "Priority", Priority.values()));
        return () -> terms;
    }

    private static <E extends Enum<E>> RequirementSchemaTerm schemaTerm(
            ReadableGraph model, String term, String ontologyClassLocalName, E[] values) {
        String definition = classComment(model, ontologyClassLocalName);
        List<String> valueNames = Arrays.stream(values).map(Enum::name).toList();
        return new RequirementSchemaTerm(term, definition, valueNames);
    }

    /** Looks up the (single, {@code @de}) class-level {@code rdfs:comment} of {@code arkreq:<localName>}. */
    private static String classComment(ReadableGraph model, String localName) {
        SimpleRdf rdf = new SimpleRdf();
        IRI subject = rdf.createIRI(REQUIREMENTS_NS + localName);
        return model.stream(subject, rdf.createIRI(RDFS_COMMENT), null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(Literal.class::cast)
                .filter(literal -> literal.getLanguageTag().map("de"::equals).orElse(false))
                .map(Literal::getLexicalForm)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing @de rdfs:comment for " + subject.getIRIString()));
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        URL resource = KognioRdfRequirementRepositoryFactory.class.getResource(classpathResource);
        Objects.requireNonNull(resource, "missing classpath resource " + classpathResource);
        try (InputStream in = resource.openStream()) {
            return new GraphImportRdf4j().readTurtle(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
