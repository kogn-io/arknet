// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

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

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Assembles a {@link KognioRdfActorRepository} over a concrete kognio-rdf dataset lifecycle.
 *
 * <p>This factory is the single place that names RDF4J-backed types: the lifecycle
 * ({@link DatasetLifecycleRdf4j}), the SHACL validation implementation
 * ({@link ShaclValidationRdf4j}) and the Turtle parsing ({@link Rio}) used to load the actor SHACL
 * shapes and ontology axioms off the classpath. It lets the composition root wire an RDF-persisted
 * actor repository by handing over just a storage directory, without itself depending on
 * {@code io.kogn.rdf.rdf4j.*} - keeping RDF4J out of arknet-mcp and preserving the port-neutrality
 * of {@link KognioRdfActorRepository} and {@link ShaclWriteGate}, which only know
 * technology-neutral kognio-rdf ports.</p>
 *
 * <p><strong>Its own gate and funnel, shared with {@code KognioRdfRoleRepository}.</strong> Unlike
 * {@code KognioRdfConstraintRepositoryFactory}, which reuses the requirements hexagon's funnel
 * because a constraint's shapes live in the very same {@code requirements-shapes.ttl}, this hexagon
 * owns both of its resource files outright ({@code actor-shapes.ttl}, {@code arknet-actor.ttl}) -
 * and, since ADR-37/kogn-io/arknet#405, so does the role resource type this same hexagon grew a
 * second aggregate for. {@link #buildFunnel} is therefore public, exactly like
 * {@code KognioRdfRequirementRepositoryFactory#buildFunnel}: built once by the composition root and
 * handed to both {@link #over(DatasetLifecycle, WriteFunnel)} and
 * {@code KognioRdfRoleRepositoryFactory#over}, rather than each building its own, functionally
 * identical gate over the same two files.</p>
 */
public final class KognioRdfActorRepositoryFactory {

    private static final String SHAPES_RESOURCE = "/actor-shapes.ttl";
    private static final String AXIOMS_RESOURCE = "/arknet-actor.ttl";

    private KognioRdfActorRepositoryFactory() {
    }

    /**
     * Creates a persistent, RDF4J-backed actor repository storing its datasets under
     * {@code storageDir}, wired for the given display language.
     *
     * @param storageDir    the directory the embedded RDF store persists into
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ActorRepository}
     */
    public static ActorRepository persistent(Path storageDir, DisplayLocale displayLocale) {
        Objects.requireNonNull(storageDir, "storageDir");
        DatasetLifecycle lifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        return over(lifecycle, displayLocale);
    }

    /**
     * Assembles an actor repository over an already-created dataset lifecycle, wired with the actor
     * SHACL write-gate and an explicit display language. Used by
     * {@link #persistent(Path, DisplayLocale)} and directly by tests that supply their own (e.g.
     * in-memory) lifecycle.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return a ready-to-use {@link ActorRepository}
     */
    public static ActorRepository over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return over(lifecycle, buildFunnel(lifecycle, displayLocale));
    }

    /**
     * Assembles an actor repository over an already-built {@link WriteFunnel} - the seam
     * {@code KognioRdfRoleRepositoryFactory#over} uses to share this hexagon's funnel rather than
     * building its own, functionally identical one (see {@link #buildFunnel}).
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from
     * @param funnel    the already-built write funnel to run every write through
     * @return a ready-to-use {@link ActorRepository}
     */
    public static ActorRepository over(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(funnel, "funnel");
        return new KognioRdfActorRepository(lifecycle, funnel);
    }

    /**
     * Builds the shared {@link WriteFunnel} every write path of the actor hexagon runs through -
     * both this factory's own repository and, over the same instance,
     * {@code KognioRdfRoleRepositoryFactory}'s (ADR-37/kogn-io/arknet#405): a {@code Role} shares
     * this hexagon's SHACL shapes and ontology axioms (both already live in
     * {@code actor-shapes.ttl}/{@code arknet-actor.ttl}), so sharing one gate and one funnel
     * instance is the point.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return the assembled write funnel
     */
    public static WriteFunnel buildFunnel(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new WriteFunnel(lifecycle, buildGate(displayLocale), WriteFunnel.DEFAULT_WRITE_CONFLICT);
    }

    /**
     * Builds the actor write-gate with RDFS reasoning enabled.
     *
     * <p>{@code actshapes:ActorShape} targets the abstract {@code arkproc:Actor}, while instances
     * are typed as one of the four concrete subclasses ({@code arkproc:HumanActor} /
     * {@code SystemActor} / {@code LegalActor} / {@code GroupActor}) - see
     * {@link KognioRdfActorRepository#typeIriFor}. Without reasoning plus the
     * {@code rdfs:subClassOf} axioms merged in via {@code axioms}, the shape would never fire and
     * every write would silently pass, exactly the trap the requirements gate already has to avoid
     * for {@code arkreq:Requirement}. The alternative - enumerating the four concrete classes as
     * {@code sh:targetClass} values, as the bounded-context and ADR gates get away with because
     * their shapes target the very type their adapters write - was rejected: it would stop covering
     * a fifth subclass the day one is added, and would leave a store-first bare
     * {@code a arkproc:Actor} resource unvalidated. {@code KognioRdfActorRepositoryTest} pins that
     * the shape does fire, by writing an actor whose name violates it.</p>
     *
     * <p>Package-private (not private) so {@code KognioRdfActorRepositoryTest} can drive the gate
     * directly, at gate level, without duplicating this shapes-loading logic.</p>
     *
     * <p>The {@code displayLocale} handed in is the same one the composition root configures
     * process-wide: a caller gets told why a write was refused in the same language regardless of
     * which bounded context rejected it, whenever the violated shape carries its {@code sh:message}
     * in more than one.</p>
     *
     * @param displayLocale the language a rejected write is reported in
     * @return the assembled actor SHACL write-gate
     */
    static ShaclWriteGate buildGate(DisplayLocale displayLocale) {
        ReadableGraph shapes = loadGraph(SHAPES_RESOURCE);
        ReadableGraph axioms = loadGraph(AXIOMS_RESOURCE);
        return new ShaclWriteGate(new ShaclValidationRdf4j(), shapes, axioms, new ValidationOptions(true),
                displayLocale);
    }

    private static ReadableGraph loadGraph(String classpathResource) {
        try (InputStream in = KognioRdfActorRepositoryFactory.class.getResourceAsStream(classpathResource)) {
            Objects.requireNonNull(in, "missing classpath resource " + classpathResource);
            Model model = Rio.parse(in, "", RDFFormat.TURTLE);
            return new RDF4JGraph(model);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + classpathResource, e);
        }
    }
}
