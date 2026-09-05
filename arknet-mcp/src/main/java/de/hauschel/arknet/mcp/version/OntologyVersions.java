// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code owl:versionInfo} of every ontology module this server ships, read off the classpath
 * (issue #194).
 *
 * <p>A backup dump is a set of triples whose meaning is defined by the ontology modules that were
 * shipped alongside the server that wrote it. Those versions are never persisted into a dataset -
 * the modules reach the runtime only as SHACL shapes and axioms for the write gate - so a dump
 * that does not carry them cannot be read back years later against a changed vocabulary with any
 * confidence. {@code project_export} therefore states them in its envelope, and this class is
 * where they come from.</p>
 *
 * <p><strong>Why a scan and not a parser.</strong> arknet-mcp is barred from RDF4J (see
 * {@code DependencyRulesTest}'s composition-root rule) and the technology-neutral kognio-rdf ports
 * carry no RDF <em>parser</em> - only a serialiser. The one place allowed to name Rio is each
 * out-adapter's repository factory, and none of them owns "all ontology modules". Rather than
 * widen an architecture rule or hand-maintain a table of version constants that would drift
 * silently, this reads the shipped files with a deliberately narrow scan of the exact two-line
 * header shape every {@code arknet-*.ttl} in {@code arknet-ontology} uses. It is not a Turtle
 * parser and must never be mistaken for one: it recognises the ontology header of arknet's own
 * files and nothing else. {@code OntologyVersionsMatchOntologyTest} in
 * {@code arknet-architecture-tests} - the one module that may parse with RDF4J - pins the result
 * of this scan against a real parse of the same files, so a reformatted header turns a test red
 * instead of silently emptying an export envelope.</p>
 *
 * <p><strong>Degrades, never throws.</strong> A resource that is missing, unreadable or does not
 * match the expected header is left out of the map. An envelope that lists six of seven modules
 * is worth more than an export that fails because a file moved.</p>
 */
public final class OntologyVersions {

    /**
     * The live ontology modules, in the order they are listed in {@code CLAUDE.md}. The parked
     * modules under {@code arknet-ontology/src/main/resources/parked/} are deliberately absent:
     * they are not on the runtime classpath and describe nothing an export can contain.
     */
    private static final List<String> MODULE_RESOURCES = List.of(
            "/arknet-core.ttl",
            "/arknet-ddd.ttl",
            "/arknet-actor.ttl",
            "/arknet-requirements.ttl",
            "/arknet-architecture.ttl",
            "/arknet-provenance.ttl",
            "/arknet-project.ttl");

    /**
     * The ontology's own subject line: an arknet IRI alone on its line, which in every shipped
     * module is immediately followed by {@code a owl:Ontology ;}.
     */
    private static final Pattern ONTOLOGY_SUBJECT =
            Pattern.compile("^<(https://w3id\\.org/arknet/[^>]+)>\\s*$");

    /** The version literal, wherever it sits inside that same header block. */
    private static final Pattern VERSION_INFO =
            Pattern.compile("owl:versionInfo\\s+\"([^\"]*)\"");

    private OntologyVersions() {
    }

    /**
     * Reads every shipped module's version.
     *
     * @return ontology IRI to {@code owl:versionInfo}, in {@link #MODULE_RESOURCES} order;
     *         modules whose resource is absent or unrecognised are omitted
     */
    public static Map<String, String> onClasspath() {
        final Map<String, String> versions = new LinkedHashMap<>();
        for (final String resource : MODULE_RESOURCES) {
            read(resource).ifPresent(module -> versions.put(module.iri(), module.version()));
        }
        return Collections.unmodifiableMap(versions);
    }

    /**
     * Reads one module. Package-private so the architecture test can drive a single resource.
     *
     * @param resource absolute classpath name, e.g. {@code /arknet-core.ttl}
     * @return the module's IRI and version, or empty if the resource is absent or carries no
     *         recognisable ontology header
     */
    static Optional<Module> read(final String resource) {
        try (InputStream in = OntologyVersions.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            return scan(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The whole of the "parsing": the first arknet IRI standing alone on a line names the module,
     * the first {@code owl:versionInfo} literal after it names its version. Both must be present.
     */
    private static Optional<Module> scan(final BufferedReader reader) throws IOException {
        String iri = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (iri == null) {
                final Matcher subject = ONTOLOGY_SUBJECT.matcher(line.strip());
                if (subject.matches()) {
                    iri = subject.group(1);
                }
                continue;
            }
            final Matcher version = VERSION_INFO.matcher(line);
            if (version.find()) {
                return Optional.of(new Module(iri, version.group(1)));
            }
        }
        return Optional.empty();
    }

    /**
     * One ontology module as the envelope states it.
     *
     * @param iri     the module's own IRI, e.g. {@code https://w3id.org/arknet/requirements}
     * @param version its {@code owl:versionInfo}
     */
    record Module(String iri, String version) {
    }
}
