// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.reactorRoot;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.shippedOntologyResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.Test;

/**
 * The rules inventory ({@code docs/rules-inventory.md}) names the place of every rule; where that
 * place is a shape, it names the shape by its prefixed name. Every such name must be a shape the
 * ontology modules ship: a renamed or removed shape would otherwise leave the inventory pointing at
 * nothing, and the rule it lists without a place.
 *
 * <p>A shape name is a prefixed name in backticks whose prefix ends in {@code shapes}, the
 * convention every shapes file follows ({@code rshapes:}, {@code ulshapes:}, {@code shapes:},
 * {@code ashapes:}, {@code actshapes:}); the prefixes resolve against the declarations of the
 * shipped shapes files themselves.</p>
 *
 * <p>The tests the inventory names as pinning a rule must exist as well: a renamed test would leave a
 * rule without the fixture the inventory claims. A test is {@code Class#method} in the last column of
 * a rule table, a bare {@code #method} belonging to the class a section names as its default; the
 * class is looked up as a {@code src/test/java} source file anywhere in the reactor. The case names
 * given in parentheses after a parametrised test class must each be the first argument of one of its
 * {@code Arguments.of} cases.</p>
 */
class RulesInventoryNamesShippedShapesTest {

    private static final Path INVENTORY = Path.of("docs", "rules-inventory.md");

    private static final Pattern SHAPE_NAME = Pattern.compile("`([a-z]*shapes):([A-Za-z][A-Za-z0-9_-]*)`");

    private static final Pattern TEST_NAME = Pattern.compile("`(?:app:|core:)?(\\w*)#(\\w+)`");

    private static final Pattern DEFAULT_CLASS = Pattern.compile("tests in `(\\w+)` unless named");

    /** A parametrised test named by its class, followed by its case names in parentheses. */
    private static final Pattern CASE_NAMES = Pattern.compile("`(\\w+Test)` \\(([^)`]+)\\)");

    /** Fewer case names than this means the pattern no longer finds them. */
    private static final int LEAST_EXPECTED_CASES = 20;

    /** Rule tables have five columns; the tests are in the last. */
    private static final int RULE_TABLE_CELLS = 5;

    /** Fewer names than this means the pattern no longer finds the inventory's shapes, not that it lists none. */
    private static final int LEAST_EXPECTED_NAMES = 50;

    @Test
    void everyShapeTheInventoryNamesIsAShippedShape() {
        // given
        Model shapes = new LinkedHashModel();
        Map<String, String> namespaces = new HashMap<>();
        shippedOntologyResources().stream().filter(resource -> resource.endsWith("-shapes.ttl")).forEach(resource -> {
            Model model = parse(resource, RulesInventoryNamesShippedShapesTest.class);
            shapes.addAll(model);
            model.getNamespaces().forEach(namespace -> namespaces.put(namespace.getPrefix(), namespace.getName()));
        });

        // when
        Set<String> named = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        Matcher matcher = SHAPE_NAME.matcher(inventory());
        while (matcher.find()) {
            String name = matcher.group(1) + ":" + matcher.group(2);
            named.add(name);
            String namespace = namespaces.get(matcher.group(1));
            if (namespace == null || shapes.filter(Values.iri(namespace + matcher.group(2)), null, null).isEmpty()) {
                missing.add(name);
            }
        }

        // then
        assertTrue(named.size() >= LEAST_EXPECTED_NAMES, "the inventory names only " + named.size()
                + " shapes: " + named);
        assertEquals(Set.of(), missing, "the inventory names shapes no shipped shapes file declares");
    }

    @Test
    void everyTestTheInventoryNamesExists() {
        // given
        Map<String, List<Path>> testSources = testSources();

        // when
        Set<String> named = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        String defaultClass = null;
        for (String line : inventory().split("\n")) {
            if (line.startsWith("## ")) {
                defaultClass = null;
            }
            Matcher section = DEFAULT_CLASS.matcher(line);
            if (section.find()) {
                defaultClass = section.group(1);
            }
            String[] cells = line.split(" \\| ");
            if (!line.startsWith("| ") || cells.length < RULE_TABLE_CELLS) {
                continue;
            }
            Matcher test = TEST_NAME.matcher(cells[cells.length - 1]);
            while (test.find()) {
                String testClass = test.group(1).isEmpty() ? defaultClass : test.group(1);
                String name = testClass + "#" + test.group(2);
                named.add(name);
                if (testClass == null || testSources.getOrDefault(testClass, List.of()).stream()
                        .noneMatch(source -> declares(source, test.group(2)))) {
                    missing.add(name);
                }
            }
        }

        // then
        assertTrue(named.size() >= LEAST_EXPECTED_NAMES, "the inventory names only " + named.size()
                + " tests: " + named);
        assertEquals(Set.of(), missing, "the inventory names tests that do not exist");
    }

    @Test
    void everyCaseTheInventoryNamesOfAParametrisedTestExists() {
        // given
        Map<String, List<Path>> testSources = testSources();

        // when
        Set<String> named = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        Matcher reference = CASE_NAMES.matcher(inventory());
        while (reference.find()) {
            String source = testSources.getOrDefault(reference.group(1), List.of()).stream()
                    .map(RulesInventoryNamesShippedShapesTest::read).collect(Collectors.joining("\n"));
            for (String name : reference.group(2).split("; ")) {
                named.add(reference.group(1) + " (" + name + ")");
                if (!source.contains("Arguments.of(\"" + name + "\"")) {
                    missing.add(reference.group(1) + " (" + name + ")");
                }
            }
        }

        // then
        assertTrue(named.size() >= LEAST_EXPECTED_CASES, "the inventory names only " + named.size()
                + " cases: " + named);
        assertEquals(Set.of(), missing, "the inventory names cases no parametrised test declares");
    }

    private static Map<String, List<Path>> testSources() {
        Path root = reactorRoot();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> root.relativize(file).toString().replace('\\', '/').contains("src/test/java/"))
                    .filter(file -> !root.relativize(file).toString().replace('\\', '/').contains("/target/"))
                    .collect(Collectors.groupingBy(file -> file.getFileName().toString().replace(".java", "")));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + root, e);
        }
    }

    private static boolean declares(final Path source, final String method) {
        return Pattern.compile("void " + Pattern.quote(method) + "\\(").matcher(read(source)).find();
    }

    private static String read(final Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + source, e);
        }
    }

    private static String inventory() {
        Path file = reactorRoot().resolve(INVENTORY);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
