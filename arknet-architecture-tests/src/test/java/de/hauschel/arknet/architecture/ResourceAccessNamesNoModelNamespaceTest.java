// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.shippedOntologyResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.Namespace;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import de.hauschel.arknet.resource.domain.ArkresVocabulary;

/**
 * Nails down the criterion of ADR-69 where ArchUnit cannot see it: no class of generic resource
 * access names an IRI from the namespace of a bounded context, or from the shared utility
 * vocabulary {@code arknet:}.
 *
 * <p><strong>Why a scan of the class files.</strong> {@code DependencyRulesTest} rule 16 sees
 * types. An IRI is a string; written down as a literal, or as a constant of another class that
 * the compiler inlines, it leaves no dependency in the bytecode -- only an entry in the constant
 * pool of the class that uses it. This test reads that constant pool: the class file bytes of
 * every production class under {@code de.hauschel.arknet.resource}, searched for each forbidden
 * namespace. Constant-pool strings are stored in modified UTF-8, which is byte-identical to ASCII
 * for every IRI arknet publishes.</p>
 *
 * <p><strong>Why the forbidden list is derived, not kept.</strong> The namespaces come from the
 * prefix declarations of every ontology and shapes file the ontology modules ship -- the
 * namespaces of the bounded contexts, their shapes and {@code arknet:} -- minus the component's
 * own {@code arkres:}. A new namespace is covered the moment a shipped file binds a prefix to
 * it; a hand-kept list would be the forgotten line this test exists to catch.</p>
 *
 * <p><strong>What stays open.</strong> An IRI assembled at runtime from fragments (a namespace
 * split across two literals) does not appear in one piece and passes. That is deliberate
 * evasion rather than an accident, and it is a matter for review.</p>
 */
class ResourceAccessNamesNoModelNamespaceTest {

    private static final String ARKNET_BASE = "https://w3id.org/arknet/";

    private static final JavaClasses RESOURCE_ACCESS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.hauschel.arknet.resource");

    private static final Set<String> FORBIDDEN_NAMESPACES = forbiddenNamespaces();

    @Test
    void noClassOfTheComponentNamesANamespaceOfTheModel() {
        final TreeMap<String, Set<String>> violations = new TreeMap<>();
        for (final JavaClass javaClass : RESOURCE_ACCESS) {
            final Set<String> named = namespacesNamedIn(classFileOf(javaClass));
            if (!named.isEmpty()) {
                violations.put(javaClass.getName(), named);
            }
        }

        assertTrue(violations.isEmpty(),
                "generic resource access names no class and no property of a bounded context or of "
                        + "arknet: in its code (ADR-69); it reads them at runtime from the ontology "
                        + "and the shapes. Found: " + violations);
    }

    /** Every module of the component is on the classpath, or the scan above proves nothing. */
    @Test
    void theScanSeesAClassOfEveryModuleOfTheComponent() {
        final Set<String> packages = RESOURCE_ACCESS.stream()
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());

        assertTrue(packages.stream().anyMatch(p -> !p.contains(".adapter")),
                "no class of arknet-resource-access-core seen: " + packages);
        assertTrue(packages.contains("de.hauschel.arknet.resource.adapter.kogniordf"),
                "no class of arknet-resource-access-adapter-kogniordf seen: " + packages);
        assertTrue(packages.contains("de.hauschel.arknet.resource.adapter.mcp"),
                "no class of arknet-resource-access-adapter-mcp seen: " + packages);
    }

    /**
     * The derived list reaches the contexts and {@code arknet:}, and leaves out {@code arkres:}.
     * Without this a broken derivation -- an empty list -- would turn the scan green for any
     * class.
     */
    @Test
    void theForbiddenNamespacesAreThoseOfTheModelAndNotTheComponentsOwn() {
        assertTrue(FORBIDDEN_NAMESPACES.contains(ARKNET_BASE + "core#"),
                "arknet: must be forbidden: " + FORBIDDEN_NAMESPACES);
        assertTrue(FORBIDDEN_NAMESPACES.contains(ARKNET_BASE + "requirements#"),
                "arkreq: must be forbidden: " + FORBIDDEN_NAMESPACES);
        assertTrue(FORBIDDEN_NAMESPACES.contains(ARKNET_BASE + "architecture#"),
                "arkarch: must be forbidden: " + FORBIDDEN_NAMESPACES);
        assertFalse(FORBIDDEN_NAMESPACES.contains(ArkresVocabulary.NAMESPACE),
                "arkres: is the component's own vocabulary: " + FORBIDDEN_NAMESPACES);
    }

    /** The scan bites: an IRI of a context in a constant pool is found, one of arkres: is not. */
    @Test
    void theScanFindsAModelIriAndPassesAnArkresIri() {
        final byte[] modelIri = ("constant pool " + ARKNET_BASE + "requirements#Requirement")
                .getBytes(StandardCharsets.ISO_8859_1);
        final byte[] ownIri = ("constant pool " + ArkresVocabulary.CREATE)
                .getBytes(StandardCharsets.ISO_8859_1);

        assertEquals(Set.of(ARKNET_BASE + "requirements#"), namespacesNamedIn(modelIri));
        assertEquals(Set.of(), namespacesNamedIn(ownIri));
    }

    private static Set<String> namespacesNamedIn(final byte[] classFile) {
        final String constants = new String(classFile, StandardCharsets.ISO_8859_1);
        return FORBIDDEN_NAMESPACES.stream()
                .filter(constants::contains)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static byte[] classFileOf(final JavaClass javaClass) {
        try (InputStream in = javaClass.getSource().orElseThrow().getUri().toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the class file of " + javaClass.getName(), e);
        }
    }

    private static Set<String> forbiddenNamespaces() {
        final Set<String> namespaces = new TreeSet<>();
        for (final String resource : shippedOntologyResources()) {
            parse(resource, ResourceAccessNamesNoModelNamespaceTest.class).getNamespaces().stream()
                    .map(Namespace::getName)
                    .filter(name -> name.startsWith(ARKNET_BASE))
                    .filter(name -> !name.equals(ArkresVocabulary.NAMESPACE))
                    .forEach(namespaces::add);
        }
        return namespaces;
    }
}
