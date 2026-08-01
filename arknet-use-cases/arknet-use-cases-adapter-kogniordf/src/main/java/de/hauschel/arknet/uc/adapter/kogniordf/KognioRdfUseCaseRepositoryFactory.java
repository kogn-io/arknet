// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;

/**
 * Assembles a {@link KognioRdfUseCaseRepository} over a concrete kognio-rdf dataset
 * lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the
 * use-case SHACL shapes and ontology axioms onto the classpath. It lets the composition root
 * wire an RDF-persisted use-case repository by handing over just a storage directory, without
 * itself depending on {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-mcp and
 * preserving the port-neutrality of {@link KognioRdfUseCaseRepository} and
 * {@link ShaclWriteGate}, which only know technology-neutral kognio-rdf ports.</p>
 */
public final class KognioRdfUseCaseRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/requirements-shapes.ttl";
    private static final String AXIOMS_RESOURCE = "/arknet-requirements.ttl";

    private static final IRI USE_CASE_CLASS =
            SimpleValueFactory.getInstance().createIRI("https://w3id.org/arknet/requirements#UseCase");
    private static final IRI STEP_CLASS =
            SimpleValueFactory.getInstance().createIRI("https://w3id.org/arknet/requirements#Step");

    private KognioRdfUseCaseRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed use-case repository storing its datasets under
     * {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link UseCaseRepository}
     */
    public static UseCaseRepository persistent(Path storageDir, DisplayLocale displayLocale) {
        Objects.requireNonNull(storageDir, "storageDir");
        final DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle, new UuidResourceIdFactory(), displayLocale);
    }

    /**
     * Assembles a use-case repository over an already-created dataset lifecycle, wired with the
     * use-case SHACL write-gate and an explicit display language. Used by
     * {@link #persistent(Path, DisplayLocale)} and directly by tests that supply their own
     * (e.g. in-memory) lifecycle.
     *
     * <p>This is the seam the composition root (arknet-mcp) uses: it passes the single shared
     * {@link DatasetLifecycle} bean so the use-case repository reads and writes the <em>same</em>
     * per-project store as the requirements and ubiquitous-language repositories - which is
     * what makes the strict cross-bounded-context label resolution (a use-case step realising an
     * {@code FR-1}, or naming an actor term) actually find those resources. Tests likewise supply
     * their own (e.g. in-memory) lifecycle.</p>
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from
     * @param resourceIdFactory mints the opaque IRI of each derived step resource; the same
     *                          kernel-owned scheme the composition root uses everywhere else
     * @param displayLocale     the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link UseCaseRepository}
     */
    public static UseCaseRepository over(
            DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        Objects.requireNonNull(displayLocale, "displayLocale");
        ShaclWriteGate gate = buildGate(displayLocale);
        WriteFunnel funnel = new WriteFunnel(lifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        return new KognioRdfUseCaseRepository(lifecycle, resourceIdFactory, funnel);
    }

    /**
     * Builds the use-case write-gate with RDFS reasoning enabled.
     *
     * <p>The use-case/step shapes ({@code UseCaseShape}, {@code StepShape}) target the same
     * abstract {@code arkreq:UseCase}/{@code arkreq:Step} classes this adapter types its
     * instances as, so the shapes fire directly. Reasoning plus the axioms are still supplied
     * for the {@code arkreq:stepRealises rdfs:subPropertyOf oslc_rm:satisfies} relation and to
     * stay symmetric with the requirements adapter.</p>
     *
     * <p>Package-private (not {@code private}) so {@code KognioRdfUseCaseRepositoryTest} can
     * exercise the wired gate directly against synthetic candidate graphs, without a
     * {@link UseCaseRepository} round-trip the single-valued {@code primaryActor} domain field
     * cannot produce.</p>
     *
     * <p>The {@code displayLocale} handed in is the same one the composition root configures
     * process-wide: a caller gets told why a write was refused in the same language regardless of
     * which bounded context rejected it, whenever the violated shape carries its {@code sh:message}
     * in more than one.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled use-case SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadUseCaseShapes();
        ReadableGraph axioms = loadGraph(AXIOMS_RESOURCE);
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, new ValidationOptions(true),
                displayLocale);
    }

    /**
     * Loads {@code requirements-shapes.ttl} but keeps only the {@code arkreq:UseCase} and
     * {@code arkreq:Step} node shapes active.
     *
     * <p>The file bundles all requirements-context shapes. A use-case write must only be
     * validated against the use-case/step shapes - not against {@code RequirementShape},
     * {@code GoalShape}, ... The adapter supplies minimal {@code rdf:type} assertions for the
     * referenced actor/requirement nodes so the {@code sh:class} constraints on
     * {@code primaryActor}/{@code stepRealises} are satisfied; without disabling the foreign
     * node shapes, those very type assertions would (correctly) pull the referenced resources
     * into e.g. {@code RequirementShape} and fail validation on data this adapter neither owns
     * nor carries. Disabling is done generically by removing every {@code sh:targetClass}
     * whose class is not {@code arkreq:UseCase}/{@code arkreq:Step}, so it needs no maintenance
     * when new requirements shapes are added.</p>
     *
     * @return the filtered shapes graph
     */
    private static ReadableGraph loadUseCaseShapes() {
        Model model = parse(SHAPES_RESOURCE);
        List<Statement> foreignTargets = model.filter(null, SHACL.TARGET_CLASS, null).stream()
                .filter(st -> !isUseCaseOrStep(st.getObject()))
                .toList();
        model.removeAll(foreignTargets);
        return new RDF4JGraph(model);
    }

    private static boolean isUseCaseOrStep(Value target) {
        return USE_CASE_CLASS.equals(target) || STEP_CLASS.equals(target);
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        return new RDF4JGraph(parse(classpathResource));
    }

    private static Model parse(String classpathResource) {
        try (InputStream in = KognioRdfUseCaseRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            return Rio.parse(in, "", RDFFormat.TURTLE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
