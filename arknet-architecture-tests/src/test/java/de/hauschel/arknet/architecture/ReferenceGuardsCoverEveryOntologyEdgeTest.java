// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.iri;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.shippedOntologyResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepository;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfRoleRepository;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepository;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepository;

/**
 * Nails down that a resource cannot be deleted out from under an edge nobody remembered to list.
 *
 * <p>Three out-adapters refuse a delete while something still points at the resource:
 * {@code KognioRdfTermRepository} for glossary terms (issue #335), {@code KognioRdfActorRepository}
 * for actors (issue #336) and {@code KognioRdfConstraintRepository} for constraints
 * (kogn-io/arknet#481). Each carries a hand-written {@code REFERENCING_PREDICATES} map of the
 * predicates it looks for - and each of those maps is written in one bounded context while the
 * edges it must know about are written in others. Nothing ties them together.</p>
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
 * reviewer attention. The constraint edge is derived from the shipped shapes instead
 * ({@code sh:path oslc_rm:constrainedBy} with {@code sh:class arkreq:Constraint}): the property
 * is borrowed from OSLC RM, and an {@code rdfs:range} axiom on it would be a global claim about
 * the foreign vocabulary, not a statement about arknet's use of it - the shapes are where
 * arknet's local rule lives.</p>
 *
 * <p><strong>Why reflection.</strong> Both maps are private implementation detail of their
 * adapter, and widening them to public just so a test can read them would turn an internal into
 * API. Reading the field reflectively keeps the visibility honest; a rename or move fails this
 * test loudly rather than silently skipping the check.</p>
 */
class ReferenceGuardsCoverEveryOntologyEdgeTest {

    /** The glossary term class every term-referencing property declares as its range. */
    private static final String TERM_CLASS = ArkreqVocabulary.CONCEPT_TYPE;

    /** The actor class every actor-referencing property declares as its range (kogn-io/arknet#148). */
    private static final String ACTOR_CLASS = ArkprocVocabulary.ACTOR_TYPE;

    /**
     * The role class a future property would declare as its range once one exists
     * (ADR-37/kogn-io/arknet#405 Part C: {@code arkreq:primaryActor}/{@code supportingActor} are
     * planned to be repointed here). Nothing ranges over it today, so
     * {@link #everyPropertyRangingOverARoleBlocksTheRolesDeletion} carries no non-empty assertion -
     * it exists so the day one ships without updating {@code KognioRdfRoleRepository
     * #REFERENCING_PREDICATES}, this test - not a later audit - is what turns red.
     */
    private static final String ROLE_CLASS = ArkprocVocabulary.ROLE_TYPE;

    /**
     * The constraint class every constraint-referencing property shape constrains its target to
     * via {@code sh:class} (kogn-io/arknet#481).
     */
    private static final String CONSTRAINT_CLASS = ArkreqVocabulary.CONSTRAINT_TYPE;

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
     * The role-side counterpart, guarding {@code role_delete} the same way - vacuously true today
     * (no property ranges over {@code arkproc:Role} yet), unlike its sibling tests, which is exactly
     * the point: see {@link #ROLE_CLASS}'s own javadoc.
     */
    @Test
    void everyPropertyRangingOverARoleBlocksTheRolesDeletion() {
        Set<String> pointingAtRoles = propertiesRangingOver(ROLE_CLASS);

        assertTrue(referencingPredicatesOf(KognioRdfRoleRepository.class).keySet().containsAll(pointingAtRoles),
                () -> "role_delete would not notice these edges: "
                        + new TreeSet<>(missing(pointingAtRoles, KognioRdfRoleRepository.class))
                        + " - add them to KognioRdfRoleRepository.REFERENCING_PREDICATES");
    }

    /**
     * The constraint-side counterpart, guarding {@code constraint_delete} the same way
     * (kogn-io/arknet#481) - read off the shapes rather than an {@code rdfs:range} axiom, see the
     * class comment.
     */
    @Test
    void everyPropertyShapedToAConstraintBlocksTheConstraintsDeletion() {
        Set<String> pointingAtConstraints = propertiesShapedTo(CONSTRAINT_CLASS);

        assertFalse(pointingAtConstraints.isEmpty(),
                "no property shape with sh:class arkreq:Constraint found - the shapes were not loaded");
        assertTrue(referencingPredicatesOf(KognioRdfConstraintRepository.class).keySet()
                        .containsAll(pointingAtConstraints),
                () -> "constraint_delete would not notice these edges: "
                        + new TreeSet<>(missing(pointingAtConstraints, KognioRdfConstraintRepository.class))
                        + " - add them to KognioRdfConstraintRepository.REFERENCING_PREDICATES");
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
        for (Class<?> repository : Set.of(
                KognioRdfTermRepository.class, KognioRdfActorRepository.class, KognioRdfConstraintRepository.class,
                KognioRdfRoleRepository.class)) {
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

    /**
     * The {@code sh:path} of every property shape in the shipped shapes that constrains its
     * target to {@code sh:class <targetClass>} - the derivation for edges over a borrowed
     * property, where no arknet-owned {@code rdfs:range} axiom exists to read.
     */
    private Set<String> propertiesShapedTo(String targetClass) {
        return ontologies.filter(null, SHACL.CLASS, iri(targetClass)).subjects().stream()
                .flatMap(shape -> ontologies.filter(shape, SHACL.PATH, null).objects().stream())
                .map(Value::stringValue)
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
            throw new IllegalStateException("no ontology resource found on the classpath");
        }
        return merged;
    }
}
