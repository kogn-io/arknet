// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.dataset.GraphImport;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.rdf4j.dataset.GraphImportRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.HandleResolver;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.resource.application.DescribeService;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.out.HandleLookup;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * Assembles generic resource access against the kognio-rdf store - the only class of the component
 * that names RDF4J.
 *
 * <p><strong>One gate over every resource type.</strong> The shapes of all model files are
 * validated as one graph, with the model ontologies merged into the data and subclass reasoning on,
 * so a shape targeting a superclass ({@code Requirement} for a functional requirement) fires. The
 * typed out-adapters assert a referenced neighbour by its type alone and filter foreign shapes out
 * of their gate; one gate over everything cannot, since the type would activate the neighbour's
 * own node shape. The engine asserts the neighbour's real slice instead.</p>
 *
 * <p>The files are listed by name: a file name is no class or property of a bounded context, and
 * a shapes file that is added has to be listed here to take part. {@code project-shapes.ttl} stays
 * out - the project registry lives in the system dataset, which generic access never writes.</p>
 */
public final class KognioRdfResourceRepositoryFactory {

    /** The shape files of the resource types generic access writes, with their {@code arkres:} rule data. */
    static final List<String> SHAPE_RESOURCES = List.of("/requirements-shapes.ttl", "/ul-shapes.ttl",
            "/architecture-shapes.ttl", "/actor-shapes.ttl", "/arknet-shapes.ttl");

    /** The model ontologies whose axioms the gate reasons over. */
    static final List<String> ONTOLOGY_RESOURCES = List.of("/arknet-core.ttl", "/arknet-ddd.ttl",
            "/arknet-actor.ttl", "/arknet-requirements.ttl", "/arknet-architecture.ttl");

    /** The vocabulary of generic access itself: its labels and comments explain the activities. */
    static final String RESOURCE_ONTOLOGY = "/arknet-resource.ttl";

    private KognioRdfResourceRepositoryFactory() {
    }

    /**
     * The store side of generic resource access over a dataset lifecycle the caller owns.
     *
     * @param lifecycle     the dataset lifecycle every project dataset is acquired from
     * @param displayLocale the language a rejected write is reported in
     * @return the repository
     */
    public static ResourceRepository over(final DatasetLifecycle lifecycle, final DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new KognioRdfResourceRepository(buildFunnel(lifecycle, displayLocale));
    }

    /**
     * The handle lookup over the same dataset lifecycle: a bare code resolves the way
     * {@code resource_get} resolves it.
     *
     * @param lifecycle the dataset lifecycle every project dataset is acquired from
     * @return the lookup
     */
    public static HandleLookup handles(final DatasetLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        return new KognioRdfHandleLookup(new HandleResolver(new StoreReader(lifecycle), Prefixes.defaults()));
    }

    /**
     * The funnel generic access writes through, with the one gate over every resource type.
     *
     * @param lifecycle     the dataset lifecycle
     * @param displayLocale the language a rejected write is reported in
     * @return the funnel
     */
    static VersionedWriteFunnel buildFunnel(final DatasetLifecycle lifecycle, final DisplayLocale displayLocale) {
        ShaclWriteGate gate = new ShaclWriteGate(new ShaclValidationRdf4j(), shapes(), union(ONTOLOGY_RESOURCES),
                new ValidationOptions(true), displayLocale);
        return new VersionedWriteFunnel(lifecycle, gate, ContentGraph.GRAPH_IRI,
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT);
    }

    /**
     * The resource types and owned fields, read off the shapes' rule data.
     *
     * @return the schema
     */
    public static ResourceSchema schema() {
        return ResourceSchema.of(shapes());
    }

    /**
     * The Turtle reader the engine parses a submitted document with.
     *
     * @return the reader
     */
    public static GraphImport parser() {
        return new GraphImportRdf4j();
    }

    /**
     * The union of the shape files.
     *
     * @return the shapes
     */
    static ReadableGraph shapes() {
        return union(SHAPE_RESOURCES);
    }

    /**
     * The type descriptions, read off the shapes and the ontologies of the model and of {@code arkres:}.
     *
     * @param displayLocale the display language when a call names none
     * @return the describing in-port
     */
    public static DescribeResourceType describer(final DisplayLocale displayLocale) {
        Objects.requireNonNull(displayLocale, "displayLocale");
        ReadableGraph shapes = shapes();
        return new DescribeService(shapes, union(describedOntologies()), ResourceSchema.of(shapes), prefixes(),
                displayLocale);
    }

    /**
     * The prefixes the shipped ontologies and shapes declare, each with the namespace it stands for.
     *
     * <p>Read off the {@code @prefix} lines of the files, so no namespace is written down here. The
     * ontologies are read first; a prefix a later file binds to a different namespace, and a second
     * prefix for a namespace already bound, are left out, so every prefix and every namespace has one
     * reading.</p>
     *
     * @return the bindings
     */
    public static PrefixBindings prefixes() {
        Map<String, String> byPrefix = new LinkedHashMap<>();
        Set<String> namespaces = new HashSet<>();
        Stream.concat(describedOntologies().stream(), SHAPE_RESOURCES.stream())
                .flatMap(resource -> namespaces(resource).stream())
                .filter(binding -> !binding.getPrefix().isEmpty())
                .filter(binding -> !byPrefix.containsKey(binding.getPrefix()))
                .filter(binding -> namespaces.add(binding.getName()))
                .forEach(binding -> byPrefix.put(binding.getPrefix(), binding.getName()));
        return new PrefixBindings(byPrefix);
    }

    private static List<String> describedOntologies() {
        return Stream.concat(ONTOLOGY_RESOURCES.stream(), Stream.of(RESOURCE_ONTOLOGY)).toList();
    }

    private static Set<Namespace> namespaces(final String classpathResource) {
        URL resource = KognioRdfResourceRepositoryFactory.class.getResource(classpathResource);
        Objects.requireNonNull(resource, "missing classpath resource " + classpathResource);
        try (InputStream in = resource.openStream()) {
            return Rio.parse(in, "", RDFFormat.TURTLE).getNamespaces();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }

    private static Graph union(final List<String> resources) {
        Graph union = new SimpleRdf().createGraph();
        resources.forEach(resource -> load(resource).stream().forEach(union::add));
        return union;
    }

    private static Graph load(final String classpathResource) {
        URL resource = KognioRdfResourceRepositoryFactory.class.getResource(classpathResource);
        Objects.requireNonNull(resource, "missing classpath resource " + classpathResource);
        try (InputStream in = resource.openStream()) {
            return new GraphImportRdf4j().readTurtle(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
