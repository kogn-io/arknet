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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import de.hauschel.arknet.actor.adapter.mcp.ActorMcpTools;
import de.hauschel.arknet.actor.adapter.mcp.RoleMcpTools;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools;
import de.hauschel.arknet.bc.adapter.mcp.BoundedContextMcpTools;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.prj.adapter.mcp.ProjectMcpTools;
import de.hauschel.arknet.req.adapter.mcp.ConstraintMcpTools;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;

/**
 * Nails down that the stale-translation signal (kogn-io/arknet#474) names its fields the way the
 * store does.
 *
 * <p>Each {@code *_update} tool reports the multilingual fields it writes to {@code
 * StaleTranslationHint} under a hand-written key - the local name of the predicate behind the
 * field, or of the edge owning a child resource's text - and the composition root's {@code
 * StoreFieldLanguageLookup} answers under the local names it finds in the store. Nothing else
 * ties the two together: every adapter test stubs the lookup, so it only proves that the key the
 * adapter passed comes back, not that {@code useCasePrecondition} or {@code adrContext} is the
 * name of a predicate anything ever writes. A typo, or a predicate renamed in the ontology
 * without the adapter following, would not fail a single test - the hint would simply never
 * mention that field again, which is the same drift {@code arknet-architecture-tests} holds the
 * vocabulary constants against elsewhere.</p>
 *
 * <p>The shipped shapes already say which fields are multilingual: a property is one exactly
 * when its property shape carries {@code sh:uniqueLang true}. They equally say which edges pool
 * a child's text - those whose {@code rdfs:range} is an <em>owned child</em> class, meaning one
 * whose shape both declares multilingual fields and makes a position mandatory (see {@link
 * #childEdgesOf} for why a mandatory position is the right criterion and not a coincidence).
 * That is sharp enough to tell {@code acceptanceCriterion} from a neighbouring first-class
 * resource such as {@code usesTerm}, so each adapter's key list is held against fields and edges
 * alike, in both directions: every key has to be a multilingual field or an owned child edge of
 * the resource the adapter writes, and every one of those the shapes declare has to be listed -
 * a forgotten one would mute the signal for that field as silently as a typo.</p>
 *
 * <p>Mutation belief (2026-09-07): misspelling {@code RequirementMcpTools}' rationale key as
 * {@code "rational"} turns both directions red - "reports [rational] ... no such multilingual
 * field" and "never reports [rationale]" - confirming the guard depends on the list rather than
 * passing regardless. Adding {@code "usesTerm"} to {@code AdrMcpTools}' list turns the first
 * direction red ("reports [usesTerm] ... no such multilingual field or child edge"), which it did
 * not before the owned-child criterion; dropping {@code "consequence"} from that same list turns
 * the second red ("never reports [consequence]"), which it could not before edges were checked in
 * that direction at all.</p>
 *
 * <p><strong>Why reflection.</strong> The key lists are private implementation detail of their
 * adapter; reading them reflectively keeps the visibility honest, the same way {@link
 * ReferenceGuardsCoverEveryOntologyEdgeTest} reads the delete guards' predicate maps.</p>
 */
class StaleTranslationFieldsMatchShapesTest {

    /** The one multilingual field an adapter deliberately leaves out, and why. */
    private static final Set<String> TERM_LABEL_NEVER_STALE = Set.of("prefLabel");

    private final Model ontologies = parseShippedOntologies();

    static Stream<Arguments> updateTools() {
        return Stream.of(
                // The requirement shapes target arkreq:Requirement, the superclass every written
                // requirement type shares - reached here the way SHACL reaches it, via rdfs:subClassOf.
                Arguments.of(RequirementMcpTools.class, ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE, Set.of()),
                Arguments.of(ConstraintMcpTools.class, ArkreqVocabulary.CONSTRAINT_TYPE, Set.of()),
                Arguments.of(UseCaseMcpTools.class, ArkreqVocabulary.USE_CASE_TYPE, Set.of()),
                // prefLabel is the same word under every tag (FR-10): a rename rewrites every
                // variant at once, and a label under an explicit language must equal the one
                // there or be rejected - nothing older is ever left standing.
                Arguments.of(UbiquitousLanguageMcpTools.class, ArkreqVocabulary.CONCEPT_TYPE, TERM_LABEL_NEVER_STALE),
                Arguments.of(RoleMcpTools.class, ArkprocVocabulary.ROLE_TYPE, Set.of()),
                Arguments.of(AdrMcpTools.class, ArkarchVocabulary.ADR_TYPE, Set.of()),
                Arguments.of(ProjectMcpTools.class, ArkprjVocabulary.PROJECT_TYPE, Set.of()),
                // kogn-io/arknet#520: the last two untagged prosa fields became language-tagged.
                Arguments.of(ActorMcpTools.class, ArkprocVocabulary.ACTOR_TYPE, Set.of()),
                Arguments.of(BoundedContextMcpTools.class, ArkdddVocabulary.BOUNDED_CONTEXT_TYPE, Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateTools")
    void everyKeyNamesAMultilingualFieldOrAChildEdgeOfTheResource(Class<?> adapter, String resourceClass,
            Set<String> deliberatelyOmitted) {
        Set<String> declared = new HashSet<>(multilingualFieldsOf(resourceClass));
        declared.addAll(childEdgesOf(resourceClass));
        assertFalse(declared.isEmpty(),
                "no sh:uniqueLang property found for " + resourceClass + " - the shapes were not loaded");

        Set<String> unknown = new TreeSet<>(multilingualFieldsListedBy(adapter));
        unknown.removeAll(declared);

        assertTrue(unknown.isEmpty(), () -> adapter.getSimpleName() + " reports " + unknown
                + " to the stale-translation signal, but the shipped shapes declare no such multilingual "
                + "field or child edge for " + resourceClass + " - the key would never match a store answer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateTools")
    void everyMultilingualFieldTheShapesDeclareIsListed(Class<?> adapter, String resourceClass,
            Set<String> deliberatelyOmitted) {
        Set<String> expected = new TreeSet<>(multilingualFieldsOf(resourceClass));
        expected.addAll(childEdgesOf(resourceClass));
        expected.removeAll(deliberatelyOmitted);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(multilingualFieldsListedBy(adapter));

        assertTrue(missing.isEmpty(), () -> adapter.getSimpleName() + " never reports " + missing
                + " to the stale-translation signal although the shipped shapes declare them multilingual "
                + "fields, or owned child edges, of " + resourceClass + " - a single-language write there "
                + "would leave the other language behind without a word");
    }

    /** The omission fixture must describe a real multilingual field, or it documents nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("updateTools")
    void aDeliberateOmissionNamesARealMultilingualField(Class<?> adapter, String resourceClass,
            Set<String> deliberatelyOmitted) {
        Set<String> declared = multilingualFieldsOf(resourceClass);
        Set<String> stale = new TreeSet<>(deliberatelyOmitted);
        stale.removeAll(declared);

        assertEquals(Set.of(), stale, () -> adapter.getSimpleName() + ": the shapes no longer declare " + stale
                + " multilingual for " + resourceClass + " - drop the omission");
    }

    /**
     * The local names of every {@code sh:path} carrying {@code sh:uniqueLang true} on a node shape
     * targeting {@code resourceClass} or one of its superclasses (a {@code sh:targetClass} reaches
     * every instance of a subclass, so the shape of {@code arkreq:Requirement} governs a written
     * {@code arkreq:FunctionalRequirement}) - what {@code StoreFieldLanguageLookup} keys a field by.
     */
    private Set<String> multilingualFieldsOf(String resourceClass) {
        return classAndSuperclassesOf(resourceClass).stream()
                .flatMap(type -> ontologies.filter(null, SHACL.TARGET_CLASS, iri(type)).subjects().stream())
                .flatMap(nodeShape -> ontologies.filter(nodeShape, SHACL.PROPERTY, null).objects().stream())
                .filter(Resource.class::isInstance)
                .map(Resource.class::cast)
                .filter(propertyShape -> ontologies.contains(propertyShape, SHACL.UNIQUE_LANG, null)
                        && ontologies.filter(propertyShape, SHACL.UNIQUE_LANG, null).objects().stream()
                                .anyMatch(value -> "true".equals(value.stringValue())))
                .flatMap(propertyShape -> ontologies.filter(propertyShape, SHACL.PATH, null).objects().stream())
                .map(Value::stringValue)
                .map(StaleTranslationFieldsMatchShapesTest::localName)
                .collect(Collectors.toSet());
    }

    /**
     * The local names of every property the ontology declares from {@code resourceClass} to an
     * <em>owned child</em> class - the edges under which the lookup pools a child's tags.
     *
     * <p>An owned child is told from a neighbouring first-class resource by the shapes alone: it
     * has multilingual fields of its own <em>and</em> its shape makes a position mandatory. That
     * second half is not a coincidence to be exploited but the property in question - a resource
     * addressed by its position rather than by a code is exactly one the caller writes as a list,
     * wholesale, which is what makes the edge, not the individual child, the thing that goes
     * stale. Across every shipped shape file, exactly four classes declare such a property
     * ({@code arkreq:Step}, {@code arkreq:AcceptanceCriterion}, {@code arkarch:Consequence},
     * {@code arkarch:ConsideredOption}) and none of the neighbouring classes an ADR, a
     * requirement or a use case points at does.</p>
     *
     * <p>Without that second half the derivation admitted every reference edge whose target
     * happens to be multilingual ({@code usesTerm}, {@code relatedTo}, {@code
     * addressesRequirement}, ...), which would have let a reference key slip into a {@code
     * MULTILINGUAL_FIELDS} list unnoticed - and the store lookup, pooling over any edge, would
     * even have answered it, reporting a linked term's definition as a field of the ADR
     * (kogn-io/arknet#537 review).</p>
     */
    private Set<String> childEdgesOf(String resourceClass) {
        return classAndSuperclassesOf(resourceClass).stream()
                .flatMap(type -> ontologies.filter(null, RDFS.DOMAIN, iri(type)).subjects().stream())
                .filter(property -> ontologies.filter(property, RDFS.RANGE, null).objects().stream()
                        .map(Value::stringValue)
                        .anyMatch(this::isOwnedChildClass))
                .map(Resource::stringValue)
                .map(StaleTranslationFieldsMatchShapesTest::localName)
                .collect(Collectors.toSet());
    }

    /**
     * Whether {@code type} is a class the owner writes as a positioned list: multilingual fields
     * of its own, plus a mandatory position property (see {@link #childEdgesOf}).
     */
    private boolean isOwnedChildClass(String type) {
        return !multilingualFieldsOf(type).isEmpty() && declaresAMandatoryPosition(type);
    }

    /** Whether a node shape targeting {@code type} makes a {@code position} property mandatory. */
    private boolean declaresAMandatoryPosition(String type) {
        return classAndSuperclassesOf(type).stream()
                .flatMap(named -> ontologies.filter(null, SHACL.TARGET_CLASS, iri(named)).subjects().stream())
                .flatMap(nodeShape -> ontologies.filter(nodeShape, SHACL.PROPERTY, null).objects().stream())
                .filter(Resource.class::isInstance)
                .map(Resource.class::cast)
                .filter(propertyShape -> ontologies.filter(propertyShape, SHACL.MIN_COUNT, null).objects().stream()
                        .anyMatch(value -> "1".equals(value.stringValue())))
                .flatMap(propertyShape -> ontologies.filter(propertyShape, SHACL.PATH, null).objects().stream())
                .map(Value::stringValue)
                .map(StaleTranslationFieldsMatchShapesTest::localName)
                .anyMatch("position"::equals);
    }

    /** {@code resourceClass} and every class it transitively declares itself a subclass of. */
    private Set<String> classAndSuperclassesOf(String resourceClass) {
        Set<String> types = new HashSet<>();
        collectSuperclasses(resourceClass, types);
        return types;
    }

    /** Named superclasses only - an OWL restriction under {@code rdfs:subClassOf} is a blank node. */
    private void collectSuperclasses(String type, Set<String> into) {
        if (into.add(type)) {
            ontologies.filter(iri(type), RDFS.SUBCLASSOF, null).objects().stream()
                    .filter(IRI.class::isInstance)
                    .map(Value::stringValue)
                    .forEach(superclass -> collectSuperclasses(superclass, into));
        }
    }

    /** Mirrors {@code StoreResource#localName}: the part after the last {@code #} or {@code /}. */
    private static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut + 1 < iri.length() ? iri.substring(cut + 1) : iri;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> multilingualFieldsListedBy(Class<?> adapter) {
        try {
            Field field = adapter.getDeclaredField("MULTILINGUAL_FIELDS");
            field.setAccessible(true);
            return Set.copyOf((List<String>) field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(adapter.getName()
                    + " no longer holds a static MULTILINGUAL_FIELDS list - this guard has to follow the "
                    + "rename, not be dropped", e);
        }
    }

    private static Model parseShippedOntologies() {
        Model merged = null;
        for (String resource : shippedOntologyResources()) {
            Model module = parse(resource, StaleTranslationFieldsMatchShapesTest.class);
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
