// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.GraphImportRdf4j;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;

/**
 * Assembles a {@link KognioRdfContextRelationshipRepository} over a concrete kognio-rdf dataset
 * lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle import ({@link GraphImportRdf4j}) used to load the DDD SHACL
 * shapes onto the classpath. It lets the composition root wire an RDF-persisted
 * context-relationship repository by handing over just a storage directory, without itself
 * depending on {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-app and preserving the
 * port-neutrality of {@link KognioRdfContextRelationshipRepository} and {@link ShaclWriteGate},
 * which only know technology-neutral kognio-rdf ports.</p>
 *
 * <p><strong>Filtered shapes, not the shared {@code KognioRdfBoundedContextRepositoryFactory}
 * gate.</strong> {@code /arknet-shapes.ttl} bundles {@code shapes:BoundedContextShape} and
 * {@code shapes:ContextRelationshipShape} together; a {@link KognioRdfContextRelationshipRepository}
 * write's validation-only asserted context types its referenced {@code upstream}/
 * {@code downstream} nodes as {@code arkddd:BoundedContext} (to satisfy
 * {@code shapes:ContextRelationship-upstream}/{@code -downstream}'s {@code sh:class} constraint) -
 * and RDF4J's SHACL Sail then also pulls those very nodes into
 * {@code shapes:BoundedContextShape}'s own validation (its {@code sh:targetClass} matches),
 * failing them for the {@code arknet:name}/{@code arkddd:domainVision} fields this adapter neither
 * owns nor carries. Reusing {@code KognioRdfBoundedContextRepositoryFactory#buildGate} unfiltered
 * would therefore reject every otherwise-valid write. {@link #loadContextRelationshipShapes()}
 * resolves this exactly the way {@code KognioRdfUseCaseRepositoryFactory#loadUseCaseShapes}
 * resolves the identical problem for {@code arkreq:primaryRole}/{@code stepRealises}: it keeps
 * {@code /arknet-shapes.ttl}'s triples but removes every {@code sh:targetClass} that is not
 * {@code arkddd:ContextRelationship}, so only {@code ContextRelationshipShape} still fires.</p>
 */
public final class KognioRdfContextRelationshipRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/arknet-shapes.ttl";

    private static final IRI TARGET_CLASS = new SimpleRdf().createIRI("http://www.w3.org/ns/shacl#targetClass");
    private static final IRI CONTEXT_RELATIONSHIP_CLASS =
            new SimpleRdf().createIRI(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE);

    private KognioRdfContextRelationshipRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed context-relationship repository storing its datasets
     * under {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ContextRelationshipRepository}
     */
    public static ContextRelationshipRepository persistent(Path storageDir, DisplayLocale displayLocale) {
        Objects.requireNonNull(storageDir, "storageDir");
        DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle, displayLocale);
    }

    /**
     * Assembles a context-relationship repository over an already-created dataset lifecycle,
     * wired with the (filtered) DDD SHACL write-gate and an explicit display language. Used by
     * {@link #persistent(Path, DisplayLocale)} and directly by tests that supply their own (e.g.
     * in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ContextRelationshipRepository}
     */
    public static ContextRelationshipRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new KognioRdfContextRelationshipRepository(lifecycle, buildFunnel(lifecycle, displayLocale));
    }

    /**
     * Builds the {@link VersionedWriteFunnel} this context's repository writes through: the
     * context's SHACL write-gate over the one content graph. Public for the composition root,
     * which also hands it to the startup migration of an earlier storage layout, so a migrated
     * context-relationship resource passes exactly the gate a regular write does.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return the funnel
     */
    public static VersionedWriteFunnel buildFunnel(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new VersionedWriteFunnel(lifecycle, buildGate(displayLocale), ContentGraph.GRAPH_IRI,
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT);
    }

    /**
     * Builds the context-relationship write-gate.
     *
     * <p>{@code shapes:ContextRelationshipShape} targets {@code arkddd:ContextRelationship}
     * directly - the type every relationship instance already carries - so no RDFS reasoning or
     * ontology axioms are needed: an empty {@code axioms} graph and {@link ValidationOptions#defaults()}
     * suffice, exactly as {@code KognioRdfBoundedContextRepositoryFactory#buildGate} decided for
     * the sibling {@code BoundedContextShape}.</p>
     *
     * <p>Package-private (not {@code private}) so {@code KognioRdfContextRelationshipRepositoryTest}
     * can drive the gate directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled context-relationship SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadContextRelationshipShapes();
        ReadableGraph axioms = new SimpleRdf().createGraph();
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, ValidationOptions.defaults(),
                displayLocale);
    }

    /**
     * Loads {@code arknet-shapes.ttl} but keeps only the {@code arkddd:ContextRelationship} node
     * shape active - see the class javadoc's "filtered shapes" note for why.
     *
     * @return the filtered shapes graph
     */
    private static ReadableGraph loadContextRelationshipShapes() {
        Graph shapes = loadGraph(SHAPES_RESOURCE);
        List<Triple> foreignTargets = shapes.stream(null, TARGET_CLASS, null)
                .filter(st -> !CONTEXT_RELATIONSHIP_CLASS.equals(st.getObject()))
                .toList();
        foreignTargets.forEach(shapes::remove);
        return shapes;
    }

    private static Graph loadGraph(String classpathResource) {
        URL resource = KognioRdfContextRelationshipRepositoryFactory.class.getResource(classpathResource);
        Objects.requireNonNull(resource, "missing classpath resource " + classpathResource);
        try (InputStream in = resource.openStream()) {
            return new GraphImportRdf4j().readTurtle(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
