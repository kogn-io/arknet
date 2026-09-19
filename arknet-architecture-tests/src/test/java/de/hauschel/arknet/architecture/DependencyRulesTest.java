// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Nails down the dependency invariants that the Maven module cut cannot express.
 *
 * <p><strong>What is deliberately absent.</strong> A rule like "a core does not depend on its
 * adapters" is not here: Maven already enforces it harder than ArchUnit could, because the
 * adapter module is simply not on the core's compile classpath. Restating it would be
 * ceremony. "No bounded context depends on another" used to be phrased the same blanket way;
 * the invariant was later precised to bind the {@code *-core} modules, not every module of a
 * bounded context. A driving (In-) adapter is the gate into its own hexagon, not part of
 * its core, and may call a neighbour bounded context's driving port --
 * {@code arknet-requirements-adapter-mcp} depends on {@code arknet-ubiquitous-language-core}
 * for exactly that (rendering a linked term's business code instead of its bare IRI in
 * {@code req_get}/{@code req_list}; see CLAUDE.md). ADR-49 retires that pattern -- every read of
 * a neighbour runs over the reader's own out-port against the neighbour's published language --
 * and rule 9 below nails the result down for the two components of the Domain Modelling context,
 * the first to be converted. Maven still enforces the narrower,
 * {@code *-core}-scoped claim without any help from this module: none of the {@code *-core}
 * POMs declares a dependency on a sibling bounded context. Every rule below guards a property
 * that lives <em>inside</em> a module or <em>across</em> a seam Maven cannot see, and that
 * would therefore erode silently -- today they hold only by reviewer attention.</p>
 *
 * <p><strong>What these rules do and do not see.</strong> They read bytecode, not source and not
 * POMs. Two things therefore stay green: an RDF4J dependency added to a module's POM but never
 * used, and even an {@code import} statement that no code references -- an unused import leaves
 * no trace in the class file. Neither is a false negative: the architectural property breaks on
 * <em>use</em>, and use is exactly what is checked. Anything that touches an RDF4J type for real
 * -- a field, a parameter, a return type, a call -- is caught. Closing the POM-level gap would
 * need {@code maven-enforcer-plugin} ({@code bannedDependencies}); that was consciously left out
 * of scope.</p>
 *
 * <p><strong>Verifying a rule still bites.</strong> A rule that can no longer turn red is
 * decoration, and one that silently matches zero classes is worse than none. The latter is
 * covered: ArchUnit fails a rule whose {@code that()} clause matches nothing, so a broken
 * classpath surfaces as a failure rather than a vacuous pass. For the former, break the
 * invariant on purpose -- add a {@code private org.eclipse.rdf4j.model.Model x;} field to
 * {@code ShaclWriteGate} (rule 1), to a {@code KognioRdf*Repository} (rule 2), to a
 * {@code *-core} class (rule 3), to a {@code *-adapter-mcp} class (rule 4), to a
 * {@code de.hauschel.arknet.mcp} class such as {@code StoreReportTools} (rule 5), or to a
 * {@code de.hauschel.arknet.kernel} class (rule 6) -- and confirm the rule fails before
 * trusting it. All six were confirmed to fail this way when introduced. Rule 13 is broken the
 * same way, by naming a neighbour's type in a {@code de.hauschel.arknet.analysis} class. Rule 12 is the one
 * rule that does not need the exercise: it turns red on any class in the kernel package whose
 * name is not on its list, which is the whole of what it claims.</p>
 *
 * <p><strong>Why tests are excluded.</strong> The rules describe production code. Test code
 * legitimately reaches for concrete technology -- the {@code KognioRdf*RepositoryTest} classes
 * build a real store via {@code DatasetLifecycleRdf4j}, which rule 2 would otherwise flag.
 * Two mechanisms keep them out: this module only sees the other modules' main artifacts, and
 * {@link ImportOption.DoNotIncludeTests} guards against test classes reaching the import
 * through any other route.</p>
 */
@AnalyzeClasses(
        packages = "de.hauschel.arknet",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DependencyRulesTest {

    /**
     * RDF4J in every shape: the library itself and kognio-rdf's RDF4J-backed implementations.
     * The technology-neutral kognio-rdf ports ({@code io.kogn.rdf.terms}, {@code .shacl},
     * {@code .dataset}) are explicitly not part of this -- they are what the neutral code
     * is allowed to know.
     */
    private static final String[] RDF4J_PACKAGES = {
            "org.eclipse.rdf4j..",
            "io.kogn.rdf.rdf4j.."
    };

    /**
     * The terms ADR-56 admits to the shared kernel, by simple name -- the whole content of
     * {@code de.hauschel.arknet.kernel}, as a list rather than as a predicate (rule 12).
     */
    private static final Set<String> SHARED_KERNEL_TERMS = Set.of(
            "ProjectId",
            "ResourceId",
            "ResourceIdFactory",
            "DefaultResourceId",
            "UuidResourceIdFactory",
            "CodeCounter",
            "CodeAssignment",
            "LanguageTag",
            "InvalidLanguageTagException",
            "MissingDefaultLanguageException",
            "DisplayLocale",
            "LocalizedLiteral");

    /** The bounded contexts, by their package abbreviation (see CLAUDE.md). */
    private static final String[] BOUNDED_CONTEXT_PACKAGES = {
            "de.hauschel.arknet.req..",
            "de.hauschel.arknet.ul..",
            "de.hauschel.arknet.uc..",
            "de.hauschel.arknet.bc..",
            "de.hauschel.arknet.adr..",
            "de.hauschel.arknet.prj..",
            "de.hauschel.arknet.actor..",
            "de.hauschel.arknet.analysis.."
    };

    /**
     * The contexts that own a piece of the architecture model, and their vocabulary modules --
     * everything model analysis reads but must not name (rule 13). Deliberately not
     * {@link #BOUNDED_CONTEXT_PACKAGES}: that set contains model analysis itself, and a rule
     * cannot ban a context from depending on its own packages.
     */
    private static final String[] MODEL_CONTEXT_PACKAGES = {
            "de.hauschel.arknet.req..",
            "de.hauschel.arknet.ul..",
            "de.hauschel.arknet.uc..",
            "de.hauschel.arknet.bc..",
            "de.hauschel.arknet.adr..",
            "de.hauschel.arknet.prj..",
            "de.hauschel.arknet.actor..",
            "de.hauschel.arknet.*.shared.."
    };

    /**
     * The hexagon interiors and the vocabulary modules together -- everything a bounded context
     * holds apart from its adapters. Rule 11 binds this set; the {@code ..adapter..} exclusion
     * there is what turns the context packages into the cores.
     */
    private static final String[] CORE_AND_VOCABULARY_PACKAGES = {
            "de.hauschel.arknet.req..",
            "de.hauschel.arknet.ul..",
            "de.hauschel.arknet.uc..",
            "de.hauschel.arknet.bc..",
            "de.hauschel.arknet.adr..",
            "de.hauschel.arknet.prj..",
            "de.hauschel.arknet.actor..",
            "de.hauschel.arknet.analysis..",
            "de.hauschel.arknet.*.shared.."
    };

    /**
     * Rule 1 -- the property that justifies {@code arknet-persistence-support} existing as its
     * own module rather than being merged into the shared kernel.
     *
     * <p>The write gate is shared by all three out-adapters, every one of which depends on
     * RDF4J. The gate itself must not: it knows only the {@code io.kogn.rdf.shacl}/{@code terms}
     * ports, while RDF4J stays in the repository factories. This hangs by a thread -- it takes
     * one {@code rdf4j-rio-turtle} in the module POM plus an import, and the property is gone
     * with no test turning red. The {@code dependency:tree} counter-check is manual and nobody
     * runs it.</p>
     */
    @ArchTest
    static final ArchRule persistence_support_stays_free_of_rdf4j =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(RDF4J_PACKAGES)
                    .because("the shared SHACL write gate is technology-neutral by design: "
                            + "it knows only the kognio-rdf ports, RDF4J lives in "
                            + "the repository factories of the out-adapters");

    /**
     * Rule 2 -- makes good on a claim the out-adapters make in their own Javadoc: the factory
     * "is the single place that names RDF4J-backed types".
     *
     * <p>This is an intra-module rule, so Maven cannot see it in principle: the adapter needs
     * RDF4J on its classpath, and nothing stops a second class in the same package from
     * reaching for it. Keeping the technology in one file is what lets the repository itself
     * stay port-neutral.</p>
     */
    @ArchTest
    static final ArchRule only_the_repository_factory_names_rdf4j =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet..adapter.kogniordf..")
                    .and().haveSimpleNameNotEndingWith("RepositoryFactory")
                    .should().dependOnClassesThat().resideInAnyPackage(RDF4J_PACKAGES)
                    .because("each out-adapter concentrates RDF4J in its repository factory, "
                            + "so the repository and the write gate it calls stay port-neutral");

    /**
     * Rule 3 -- the hexagon interiors stay technology-free.
     *
     * <p>True today by construction: each {@code *-core} POM declares nothing but
     * {@code arknet-shared-kernel} and JUnit. Maven therefore backs this rule up for the
     * <em>current</em> POMs, but it does not state it: adding a dependency to a core POM is a
     * one-line change that no test would answer. Nailed down as a rule instead of left to
     * chance -- note it bans all of {@code io.kogn}, not just the RDF4J-backed part: a core
     * that reaches even for the neutral RDF ports has left the domain.</p>
     */
    @ArchTest
    static final ArchRule bounded_context_cores_stay_free_of_rdf_technology =
            noClasses()
                    .that().resideInAnyPackage(BOUNDED_CONTEXT_PACKAGES)
                    .and().resideOutsideOfPackage("..adapter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.eclipse.rdf4j..", "io.kogn..")
                    .because("domain and application layers of a bounded context are "
                            + "technology-neutral; RDF is an out-adapter concern");

    /**
     * Rule 4 -- the driving (In-) adapters stay technology-free too, mirroring rule 3's claim
     * for the opposite side of the hexagon.
     *
     * <p>{@code ..adapter..} in rule 3's exclusion covers both the out-adapters (which need
     * RDF4J -- that is their job) and the in-adapters (which do not: {@code *-adapter-mcp}
     * translates MCP tool calls into its own bounded context's in-port, nothing more). Rule 3
     * therefore leaves the in-adapters unguarded against reaching straight past their own
     * hexagon into RDF4J or {@code io.kogn} instead of going through the out-port/service. This
     * does not forbid an in-adapter depending on a neighbour bounded context's in-port as a
     * Borrowed In-Port (e.g. {@code arknet-requirements-adapter-mcp} on
     * {@code arknet-ubiquitous-language-core}'s {@code ResolveTerms}) -- that neighbour port is
     * domain-level, not RDF technology.</p>
     */
    @ArchTest
    static final ArchRule driving_adapters_stay_free_of_rdf_technology =
            noClasses()
                    .that().resideInAPackage("..adapter.mcp..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.eclipse.rdf4j..", "io.kogn..")
                    .because("a driving MCP adapter talks to its own bounded context through "
                            + "its in-port (or, as a Borrowed In-Port, a neighbour's in-port); "
                            + "RDF is an out-adapter concern");

    /**
     * Rule 5 -- the composition root stays free of direct RDF4J, mirroring the claim its own
     * Javadoc already makes ({@code ArknetMcpConfiguration}, {@code StoreReader},
     * {@code TraceabilityGraph}; #185).
     *
     * <p>Unlike rules 3 and 4, this does not ban {@code io.kogn} wholesale: the composition
     * root's remaining generic store-read path ({@code mcp/store}, {@code mcp/search})
     * deliberately reads the technology-neutral kognio-rdf ports directly, so only {@link
     * #RDF4J_PACKAGES} -- RDF4J itself and kognio-rdf's RDF4J-backed implementations -- is
     * checked here.</p>
     */
    @ArchTest
    static final ArchRule composition_root_stays_free_of_rdf4j =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.mcp..")
                    .should().dependOnClassesThat().resideInAnyPackage(RDF4J_PACKAGES)
                    .because("arknet-mcp wires the out-adapters but does not itself reach for "
                            + "RDF4J-backed types; its generic read path uses only the "
                            + "technology-neutral kognio-rdf ports");

    /**
     * Rule 6 -- the shared kernel stays technology-free too, mirroring rule 3's claim for the
     * one module every {@code *-core} depends on.
     *
     * <p>True today by construction: {@code arknet-shared-kernel}'s POM declares nothing but
     * JUnit. Left unguarded, that would only hold by reviewer attention -- a one-line dependency
     * addition "for a helper type" would leak RDF technology into all six {@code *-core} modules
     * through their shared dependency, without any test turning red. Unlike rule 3, there is no
     * {@code ..adapter..} exception: the kernel has no adapter package.</p>
     */
    @ArchTest
    static final ArchRule shared_kernel_stays_free_of_rdf_technology =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.kernel..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.eclipse.rdf4j..", "io.kogn..")
                    .because("the shared kernel is depended on by every bounded context core and "
                            + "must stay technology-neutral for the same reason rule 3 binds the "
                            + "cores themselves");

    /**
     * Rule 7 -- a context's vocabulary module ({@code <context>-shared}) depends on nothing but
     * the shared kernel (ADR-57).
     *
     * <p>It holds the typed business codes more than one component of the context speaks: values
     * and their rules, no behaviour. Its whole value is that it is safe for two cores to share,
     * and that is exactly what one added dependency would end -- a vocabulary module that reaches
     * for a core, an adapter or a framework drags all of it into every core of the context. Maven
     * states the current POM, not the rule; phrased over the package name so it covers every
     * context's vocabulary module, present and future.</p>
     */
    @ArchTest
    static final ArchRule vocabulary_modules_depend_on_nothing_but_the_shared_kernel =
            classes()
                    .that().resideInAPackage("de.hauschel.arknet.*.shared..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "java..", "de.hauschel.arknet.kernel..", "de.hauschel.arknet.*.shared..")
                    .because("a vocabulary module is the language of its bounded context as code; "
                            + "two cores can only share it while it carries no dependency of its own");

    /**
     * Rule 8 -- a driving (In-) adapter sees only the in-ports of its core, never its application
     * services or out-ports (ADR-58).
     *
     * <p>Without an {@code api} module there is no Maven boundary between a component's in-port
     * interfaces and the rest of its core: the adapter has the whole core on its classpath and
     * could call the service class directly, or implement an out-port itself. That is the price
     * ADR-58 names for not building the module, and this rule is what it is paid with. Domain
     * types stay allowed -- an adapter renders a {@code BoundedContext}; it is the application
     * layer past {@code port.in} that is off limits.</p>
     */
    @ArchTest
    static final ArchRule driving_adapters_see_only_the_in_ports_of_their_core =
            noClasses()
                    .that().resideInAPackage("..adapter.mcp..")
                    .should().dependOnClassesThat(resideInAPackage("..application..")
                            .and(not(resideInAPackage("..application.port.in.."))))
                    .because("a driving adapter is the gate to its hexagon's in-ports; without an "
                            + "api module only this rule keeps it out of the rest of the core");

    /**
     * Rule 9 -- no module of the bounded-context component depends on a module of the
     * ubiquitous-language component, or the other way round (ADR-49).
     *
     * <p>They are the two components of the Domain Modelling context, and they share a Maven
     * parent -- which is aggregation, not permission. Everything either needs from the other it
     * reads through its own out-port from the neighbour's published language in the shared store,
     * the same mechanism it would use across a context boundary. Before ADR-49 the
     * bounded-context MCP adapter borrowed the glossary's own in-port for display; the rule below
     * is what keeps that from creeping back, on either side and in any module, not just in the
     * core. Rule 10 says the same for the components of Product &amp; Requirements.</p>
     */
    @ArchTest
    static final ArchRule bounded_context_component_stays_off_the_glossary_component =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.bc..")
                    .should().dependOnClassesThat().resideInAPackage("de.hauschel.arknet.ul..")
                    .because("a component reads its neighbour through its own out-port over the "
                            + "published language, never through one of its modules");

    /** Rule 9, the other direction: the glossary component knows nothing of its neighbour. */
    @ArchTest
    static final ArchRule glossary_component_stays_off_the_bounded_context_component =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.ul..")
                    .should().dependOnClassesThat().resideInAPackage("de.hauschel.arknet.bc..")
                    .because("a component reads its neighbour through its own out-port over the "
                            + "published language, never through one of its modules");

    /**
     * Rule 10 -- no module of the requirements component depends on a module of the use-cases
     * component, or the other way round (ADR-49).
     *
     * <p>The same invariant as rule 9, for the two components of the Product &amp; Requirements
     * context, and written the same way on purpose: two directed rules rather than one clever
     * symmetric one, so a violation names the direction it came from. Before ADR-49 the use-cases
     * MCP adapter borrowed the requirements component's own in-ports to display a realised
     * requirement's and a linked constraint's business code; since then the use-cases core asks
     * its own {@code RequirementLookup}/{@code ConstraintLookup} out-ports, served over the
     * neighbour's published language. Their shared Maven parent is aggregation, not
     * permission.</p>
     */
    @ArchTest
    static final ArchRule use_cases_component_stays_off_the_requirements_component =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.uc..")
                    .should().dependOnClassesThat().resideInAPackage("de.hauschel.arknet.req..")
                    .because("a component reads its neighbour through its own out-port over the "
                            + "published language, never through one of its modules");

    /** Rule 10, the other direction: the requirements component knows nothing of its neighbour. */
    @ArchTest
    static final ArchRule requirements_component_stays_off_the_use_cases_component =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.req..")
                    .should().dependOnClassesThat().resideInAPackage("de.hauschel.arknet.uc..")
                    .because("a component reads its neighbour through its own out-port over the "
                            + "published language, never through one of its modules");

    /**
     * Rule 11 -- no core and no vocabulary module depends on {@code arknet-mcp-support}
     * (ADR-56).
     *
     * <p>The support module of the driving adapters holds what the shared kernel used to carry
     * alongside its model terms: resolving a client anchor to a project, the stale-translation
     * signal and the field-language lookup behind it, the shapes of a writing tool's answer, the
     * shared tool parameter texts. None of it is a term any {@code *-core} speaks, and the split
     * is only worth its module as long as it stays that way. Maven backs it today -- no
     * {@code *-core} POM names the module -- but a one-line dependency would end it silently,
     * and every driving adapter has the module on its classpath already, one step from the core
     * it sits in front of.</p>
     */
    @ArchTest
    static final ArchRule cores_and_vocabulary_modules_stay_off_the_tool_adapter_support =
            noClasses()
                    .that().resideInAnyPackage(CORE_AND_VOCABULARY_PACKAGES)
                    .and().resideOutsideOfPackage("..adapter..")
                    .should().dependOnClassesThat().resideInAPackage("de.hauschel.arknet.mcpsupport..")
                    .because("anchor resolution, the translation signal and a tool's answer "
                            + "format are mechanics of the driving adapters; a core that reaches "
                            + "for them has taken the transport into the hexagon");

    /**
     * Rule 12 -- the shared kernel holds exactly the terms admitted to it (ADR-56).
     *
     * <p>ADR-56's admission rule -- a term enters only if at least two contexts carry it, and
     * that two carry it is not by itself enough -- is a judgement per term, not a property of a
     * class file. Two contexts using a type is the closest mechanical proxy, and it is the wrong
     * one in both directions: it would pass any technical helper two adapters happen to share,
     * and it would fail {@code MissingDefaultLanguageException}, which is thrown inside the
     * kernel and named by the cores in prose rather than in bytecode.</p>
     *
     * <p>So the rule is a list instead of a predicate, and that is the point: adding a type to
     * the kernel turns this test red, and the only way to green is to write the new name here.
     * The check is the review it forces, not the condition it states -- the same shape a
     * deletion-guard list or an ontology-constant comparison has elsewhere in this module.
     * Renaming or removing a term turns it red too, which is the cheap half of the same
     * bargain.</p>
     */
    @ArchTest
    static final ArchRule shared_kernel_holds_only_the_terms_admitted_to_it =
            classes()
                    .that().resideInAPackage("de.hauschel.arknet.kernel..")
                    .should(beOneOfTheAdmittedSharedKernelTerms())
                    .because("ADR-56 makes admission to the shared kernel a decision per term; "
                            + "an unlisted type in the kernel is a decision nobody made");

    /**
     * Rule 13 -- model analysis names no module of the contexts it reads (ADR-54, ADR-49).
     *
     * <p>It is the one context that knows the metamodel of every other, and it knows it the only
     * way a context may know a neighbour: as the published language in the store, read through
     * its own out-port and addressed by the {@code Ark*Vocabulary} predicate constants. The
     * moment a class here names a requirement, a term, a use case, a bounded context, a decision,
     * an actor or a project -- or one of the vocabulary modules those contexts share -- the
     * component has stopped reading a published language and started depending on its
     * neighbours, which is the construction it was cut out of the composition root to end.</p>
     *
     * <p>The rule reads bytecode, not POMs, like every rule here: a dependency declared but never
     * used stays green. Closing that half would need {@code maven-enforcer-plugin}
     * ({@code bannedDependencies}), which this module has deliberately stayed out of; today no
     * POM of the component names any of these modules, and the property breaks on use.</p>
     */
    @ArchTest
    static final ArchRule model_analysis_names_no_module_of_the_contexts_it_reads =
            noClasses()
                    .that().resideInAPackage("de.hauschel.arknet.analysis..")
                    .should().dependOnClassesThat().resideInAnyPackage(MODEL_CONTEXT_PACKAGES)
                    .because("model analysis owns no resource and reads every context over its "
                            + "published language in the store, never over one of its modules");

    private static ArchCondition<JavaClass> beOneOfTheAdmittedSharedKernelTerms() {
        return new ArchCondition<>("be one of the terms ADR-56 admits to the shared kernel") {
            @Override
            public void check(final JavaClass item, final ConditionEvents events) {
                final String topLevelName = topLevelSimpleName(item);
                events.add(new SimpleConditionEvent(item, SHARED_KERNEL_TERMS.contains(topLevelName),
                        "%s is %sadmitted to the shared kernel".formatted(item.getName(),
                                SHARED_KERNEL_TERMS.contains(topLevelName) ? "" : "not ")));
            }
        };
    }

    /**
     * The simple name of the outermost class {@code item} belongs to, so that a nested record or
     * interface counts as part of the term that encloses it rather than as a term of its own.
     */
    private static String topLevelSimpleName(final JavaClass item) {
        final String simpleName = item.getName().substring(item.getName().lastIndexOf('.') + 1);
        final int nested = simpleName.indexOf('$');
        return nested < 0 ? simpleName : simpleName.substring(0, nested);
    }
}
