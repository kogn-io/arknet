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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
class RulesInventoryNamesShippedShapesTest {

    private static final Path INVENTORY = Path.of("docs", "rules-inventory.md");

    private static final Pattern SHAPE_NAME = Pattern.compile("`([a-z]*shapes):([A-Za-z][A-Za-z0-9_-]*)`");

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

    private static String inventory() {
        Path file = reactorRoot().resolve(INVENTORY);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
