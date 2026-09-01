// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.iri;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepository;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepository;

/**
 * Nails down that a resource cannot be deleted out from under an edge nobody remembered to list.
 *
 * <p>Two out-adapters refuse a delete while something still points at the resource:
 * {@code KognioRdfTermRepository} for glossary terms (issue #335) and
 * {@code KognioRdfActorRepository} for actors (issue #336). Each carries a hand-written
 * {@code REFERENCING_PREDICATES} map of the predicates it looks for - and each of those maps is
 * written in one bounded context while the edges it must know about are written in others. Nothing
 * ties the two together.</p>
 *
 * <p><strong>Why this cannot be caught elsewhere.</strong> That is not a hypothetical:
 * {@code arkarch:usesTerm} shipped in kogn-io/arknet#393 and the term guard was never told about
 * it, so {@code term_delete} happily removed a term an architecture decision still referenced and
 * left the decision pointing into nothing (kogn-io/arknet#399). Every test in the writing BC stayed
 * green - it asserts that the edge is written, not that anyone downstream honours it - and the
 * SHACL write gate does not close the gap either, since it validates what is written, not what is
 * removed. The omission surfaced only in {@code store_overview}'s integrity line, after the fact.
 * This test closes the loop the module cut cannot: the shipped ontologies already say which
 * properties point at a term ({@code rdfs:range skos:Concept}) and which point at an actor
 * ({@code rdfs:range arkproc:Actor}), so the maps can be held against them rather than against
 * reviewer attention.</p>
 *
 * <p><strong>Why reflection.</strong> Both maps are private implementation detail of their
 * adapter, and widening them to public just so a test can read them would turn an internal into
 * API. Reading the field reflectively keeps the visibility honest; a rename or move fails this
 * test loudly rather than silently skipping the check.</p>
 */
class ReferenceGuardsCoverEveryOntologyEdgeTest {

    /**
     * One shipped ontology that is certainly on the classpath - the anchor from which the rest of
     * {@code arknet-ontology}'s root resources are enumerated, so a newly added ontology module is
     * covered without anyone editing this test.
     */
    private static final String ANCHOR_RESOURCE = "/arknet-core.ttl";

    /** The glossary term class every term-referencing property declares as its range. */
    private static final String TERM_CLASS = ArkreqVocabulary.CONCEPT_TYPE;

    /**
     * The actor class, spelled out rather than taken from a vocabulary constant: no module owns an
     * {@code arkproc:} vocabulary class today ({@code arknet-actor}'s adapter builds the IRIs from
     * its own local namespace constant), and inventing one for a test would put the vocabulary in
     * the wrong place.
     */
    private static final String ACTOR_CLASS = "https://w3id.org/arknet/process#Actor";

    private final Model ontologies = parseShippedOntologies();

    /**
     * Every property the shipped ontologies declare as pointing at a glossary term must block that
     * term's deletion. The check is one-directional on purpose: the term guard also lists
     * {@code arkreq:primaryActor}/{@code supportingActor}, edges that stopped pointing at terms
     * with issue #336 but can still sit in a store filled before that cut, so extra entries are
     * legitimate - a missing one never is.
     */
    @Test
    void everyPropertyRangingOverAGlossaryTermBlocksTheTermsDeletion() {
        Set<String> pointingAtTerms = propertiesRangingOver(TERM_CLASS);

        assertFalse(pointingAtTerms.isEmpty(),
                "no property with rdfs:range skos:Concept found - the ontologies were not loaded");
        assertTrue(referencingPredicatesOf(KognioRdfTermRepository.class).keySet().containsAll(pointingAtTerms),
                () -> "term_delete would not notice these edges: "
                        + new TreeSet<>(missing(pointingAtTerms, KognioRdfTermRepository.class))
                        + " - add them to KognioRdfTermRepository.REFERENCING_PREDICATES");
    }

    /** The actor-side counterpart, guarding {@code actor_delete} the same way. */
    @Test
    void everyPropertyRangingOverAnActorBlocksTheActorsDeletion() {
        Set<String> pointingAtActors = propertiesRangingOver(ACTOR_CLASS);

        assertFalse(pointingAtActors.isEmpty(),
                "no property with rdfs:range arkproc:Actor found - the ontologies were not loaded");
        assertTrue(referencingPredicatesOf(KognioRdfActorRepository.class).keySet().containsAll(pointingAtActors),
                () -> "actor_delete would not notice these edges: "
                        + new TreeSet<>(missing(pointingAtActors, KognioRdfActorRepository.class))
                        + " - add them to KognioRdfActorRepository.REFERENCING_PREDICATES");
    }

    /**
     * The rejection message names each blocking edge by a shorthand, and the caller picks the tool
     * that drops it from that name - so two predicates must never answer to the same one.
     * {@code arkreq:usesTerm} and {@code arkarch:usesTerm} share a local name and are dropped
     * through different tools ({@code req_update}/{@code uc_update} vs {@code adr_update}), which
     * is why the term guard's shorthands carry their namespace prefix (kogn-io/arknet#399).
     */
    @Test
    void noTwoBlockingPredicatesAnswerToTheSameShorthand() {
        for (Class<?> repository : Set.of(KognioRdfTermRepository.class, KognioRdfActorRepository.class)) {
            Collection<String> shorthands = referencingPredicatesOf(repository).values();
            assertEquals(shorthands.size(), Set.copyOf(shorthands).size(),
                    repository.getSimpleName() + ": two predicates share one shorthand, so the "
                            + "rejection message cannot tell the caller which edge to remove");
        }
    }

    private Set<String> missing(Set<String> required, Class<?> repository) {
        Set<String> gap = new HashSet<>(required);
        gap.removeAll(referencingPredicatesOf(repository).keySet());
        return gap;
    }

    /** The subjects the shipped ontologies declare with {@code rdfs:range <rangeClass>}. */
    private Set<String> propertiesRangingOver(String rangeClass) {
        return ontologies.filter(null, RDFS.RANGE, iri(rangeClass)).stream()
                .map(Statement::getSubject)
                .map(Resource::stringValue)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> referencingPredicatesOf(Class<?> repository) {
        try {
            Field field = repository.getDeclaredField("REFERENCING_PREDICATES");
            field.setAccessible(true);
            return (Map<String, String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(repository.getName()
                    + " no longer holds a static REFERENCING_PREDICATES map - this guard has to "
                    + "follow the rename, not be dropped", e);
        }
    }

    /**
     * Every {@code .ttl} at the root of {@code arknet-ontology}, parsed into one model - the live
     * modules and their shapes. The {@code parked/} subdirectory is left out by construction: its
     * files describe vocabulary no bounded context writes yet, so no delete guard owes them
     * anything.
     */
    private static Model parseShippedOntologies() {
        Model merged = null;
        for (String resource : shippedOntologyResources()) {
            Model module = parse(resource, ReferenceGuardsCoverEveryOntologyEdgeTest.class);
            if (merged == null) {
                merged = module;
            } else {
                merged.addAll(module);
            }
        }
        if (merged == null) {
            throw new IllegalStateException("no ontology resource found next to " + ANCHOR_RESOURCE);
        }
        return merged;
    }

    /**
     * Enumerates those resources off the classpath rather than listing them here: a hand-kept list
     * would be the very kind of forgotten line this test exists to catch. {@code arknet-ontology}
     * resolves to a jar in an installed build and to a {@code target/classes} directory inside the
     * reactor, so both layouts are handled.
     */
    private static Set<String> shippedOntologyResources() {
        URL anchor = ReferenceGuardsCoverEveryOntologyEdgeTest.class.getResource(ANCHOR_RESOURCE);
        if (anchor == null) {
            throw new IllegalStateException("missing classpath resource " + ANCHOR_RESOURCE);
        }
        try {
            return "jar".equals(anchor.getProtocol()) ? jarEntries(anchor) : directoryEntries(anchor);
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("failed to enumerate the shipped ontologies", e);
        }
    }

    private static Set<String> jarEntries(URL anchor) throws IOException {
        String jarPath = anchor.getPath().substring("file:".length(), anchor.getPath().indexOf('!'));
        Set<String> resources = new HashSet<>();
        try (JarFile jar = new JarFile(java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8))) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".ttl") && name.indexOf('/') < 0) {
                    resources.add("/" + name);
                }
            }
        }
        return resources;
    }

    private static Set<String> directoryEntries(URL anchor) throws URISyntaxException {
        File[] files = new File(anchor.toURI()).getParentFile().listFiles();
        Set<String> resources = new HashSet<>();
        for (File file : files == null ? new File[0] : files) {
            if (file.isFile() && file.getName().endsWith(".ttl")) {
                resources.add("/" + file.getName());
            }
        }
        return resources;
    }
}
