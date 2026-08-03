// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

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
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;

/**
 * Assembles {@link KognioRdfProjectRegistry} and {@link KognioRdfProjectSelfDescription} over a
 * concrete kognio-rdf dataset lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the project
 * SHACL shapes onto the classpath. It lets the composition root wire the project component by
 * handing over just a storage directory, without itself depending on {@code io.kogn.rdf.rdf4j.*} -
 * keeping RDF4J out of arknet-mcp and preserving the port-neutrality of
 * {@link KognioRdfProjectRegistry}, {@link KognioRdfProjectSelfDescription} and
 * {@link ShaclWriteGate}, which only know technology-neutral kognio-rdf ports. This naming
 * discipline is also an ArchUnit rule (see {@code arknet-architecture-tests}'s
 * {@code DependencyRulesTest}): only a class whose simple name ends in {@code RepositoryFactory}
 * may reference an RDF4J/kognio-rdf-rdf4j type, which is why this class keeps that suffix even
 * though it assembles two ports, not one repository.</p>
 */
public final class KognioRdfProjectRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/project-shapes.ttl";

    private KognioRdfProjectRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed {@link ProjectRegistry} storing its datasets under
     * {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ProjectRegistry}
     */
    public static ProjectRegistry registryOver(Path storageDir, DisplayLocale displayLocale) {
        return registryOver(persistentLifecycle(storageDir), displayLocale);
    }

    /**
     * Assembles a {@link ProjectRegistry} over an already-created dataset lifecycle, wired with
     * the project SHACL write-gate and an explicit display language. Used by
     * {@link #registryOver(Path, DisplayLocale)} and directly by tests that supply their own
     * (e.g. in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ProjectRegistry}
     */
    public static ProjectRegistry registryOver(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        WriteFunnel funnel = new WriteFunnel(lifecycle, buildGate(displayLocale), WriteFunnel.DEFAULT_WRITE_CONFLICT);
        return new KognioRdfProjectRegistry(lifecycle, funnel);
    }

    /**
     * Creates a persistent, RDF4J-backed {@link ProjectSelfDescription} storing its datasets
     * under {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ProjectSelfDescription}
     */
    public static ProjectSelfDescription selfDescriptionOver(Path storageDir, DisplayLocale displayLocale) {
        return selfDescriptionOver(persistentLifecycle(storageDir), displayLocale);
    }

    /**
     * Assembles a {@link ProjectSelfDescription} over an already-created dataset lifecycle, wired
     * with the project SHACL write-gate and an explicit display language. Used by
     * {@link #selfDescriptionOver(Path, DisplayLocale)} and directly by tests that supply their
     * own (e.g. in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ProjectSelfDescription}
     */
    public static ProjectSelfDescription selfDescriptionOver(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new KognioRdfProjectSelfDescription(lifecycle, buildGate(displayLocale));
    }

    private static DatasetLifecycle persistentLifecycle(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        return new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
    }

    /**
     * Builds the project write-gate.
     *
     * <p>{@code prjshapes:ProjectShape}/{@code prjshapes:AnchorShape} target
     * {@code arkprj:Project}/{@code arkprj:Anchor} directly - the types every candidate graph
     * already carries (see {@link ProjectGraphs#buildGraph}) - so no RDFS reasoning or ontology
     * axioms are needed (unlike the requirements adapter, whose shape targets an abstract
     * superclass): an empty {@code axioms} graph and {@link ValidationOptions#defaults()} suffice,
     * the same choice the bounded-context and ubiquitous-language adapters make for the same
     * reason.</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfProjectRegistryTest} can drive the gate
     * directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled project SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = new SimpleRdf().createGraph();
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, ValidationOptions.defaults(),
                displayLocale);
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfProjectRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
