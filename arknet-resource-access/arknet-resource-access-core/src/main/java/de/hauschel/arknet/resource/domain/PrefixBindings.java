// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The prefixes the shipped ontologies and shapes bind, each to its full namespace.
 *
 * <p>The bindings are data the adapter reads off the shipped files at run time; no binding is
 * written down in this component's code (ADR-69). They serve two readers: a type description
 * names fields and types by prefixed name and declares the prefixes its example uses, and a
 * rejection names the full namespace behind every prefix its text uses (ADR-59, consequence 5).</p>
 *
 * @param byPrefix the namespace bound to each prefix, without the colon
 */
public record PrefixBindings(Map<String, String> byPrefix) {

    private static final Pattern LOCAL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    /**
     * Copies the bindings, sorted by prefix.
     *
     * @param byPrefix the namespace bound to each prefix
     */
    public PrefixBindings {
        Objects.requireNonNull(byPrefix, "byPrefix");
        byPrefix = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(byPrefix)));
    }

    /**
     * No bindings.
     *
     * @return the empty bindings
     */
    public static PrefixBindings none() {
        return new PrefixBindings(Map.of());
    }

    /**
     * Expands a prefixed name, accepts a full IRI with or without angle brackets.
     *
     * @param name a prefixed name such as {@code p:Local}, an IRI, or an IRI in angle brackets
     * @return the full IRI, empty if the prefix is not bound
     */
    public Optional<String> expand(final String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.strip();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return Optional.of(trimmed.substring(1, trimmed.length() - 1));
        }
        if (trimmed.contains("://") || trimmed.startsWith("urn:")) {
            return Optional.of(trimmed);
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPrefix.get(trimmed.substring(0, colon)))
                .map(namespace -> namespace + trimmed.substring(colon + 1));
    }

    /**
     * Writes an IRI as Turtle: as a prefixed name if a binding covers it, otherwise in angle brackets.
     *
     * @param iri the IRI
     * @return the Turtle form
     */
    public String abbreviate(final String iri) {
        Objects.requireNonNull(iri, "iri");
        return byPrefix.entrySet().stream()
                .filter(binding -> iri.startsWith(binding.getValue()))
                .filter(binding -> LOCAL_NAME.matcher(iri.substring(binding.getValue().length())).matches())
                .max(Comparator.comparingInt(binding -> binding.getValue().length()))
                .map(binding -> binding.getKey() + ":" + iri.substring(binding.getValue().length()))
                .orElse("<" + iri + ">");
    }

    /**
     * The bindings a text uses: each prefix it writes as {@code prefix:} and each namespace it names in full.
     *
     * @param text a text such as a rejection
     * @return the bindings it uses
     */
    public PrefixBindings usedIn(final String text) {
        Objects.requireNonNull(text, "text");
        return new PrefixBindings(byPrefix.entrySet().stream()
                .filter(binding -> text.contains(binding.getValue())
                        || Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(binding.getKey()) + ":(?!//)")
                                .matcher(text).find())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * The bindings among these whose prefix is one of {@code prefixes}.
     *
     * @param prefixes the prefixes to keep
     * @return the kept bindings
     */
    public PrefixBindings only(final Set<String> prefixes) {
        return new PrefixBindings(byPrefix.entrySet().stream()
                .filter(binding -> prefixes.contains(binding.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * Writes the bindings as Turtle prefix declarations, one per line.
     *
     * @return the declarations, empty for no bindings
     */
    public String declarations() {
        return byPrefix.entrySet().stream()
                .map(binding -> "@prefix " + binding.getKey() + ": <" + binding.getValue() + "> .\n")
                .collect(Collectors.joining());
    }

    /**
     * Whether there is no binding.
     *
     * @return true for no bindings
     */
    public boolean isEmpty() {
        return byPrefix.isEmpty();
    }
}
