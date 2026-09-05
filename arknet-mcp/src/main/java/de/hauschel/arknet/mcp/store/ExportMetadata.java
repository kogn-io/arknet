// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import de.hauschel.arknet.mcp.version.ServerVersion;
import de.hauschel.arknet.persistence.ExportMetadataVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;

/**
 * The envelope {@link StoreExporter} appends to every {@code .trig} dump: which server wrote it,
 * when, and against which ontology module versions (issue #194).
 *
 * <p><strong>Output, not state.</strong> These triples are never written into a dataset and never
 * validated by the SHACL write gate. They exist in the serialised text only, in the single named
 * graph {@link ExportMetadataVocabulary#EXPORT_METADATA_GRAPH}, appended after the dataset's own
 * graphs. Keeping them in a graph of their own is what makes the dump honest in both directions: a
 * reader wanting the store alone drops one graph, and no model or provenance graph is handed a
 * statement the store never held. ADR-26 in arknet's own store keeps the dump a backup rather
 * than a diffable export, so the timestamp changing on every run is expected, not churn to be
 * avoided.</p>
 *
 * <p><strong>Why the ontology versions belong here.</strong> The shipped {@code arknet-*.ttl}
 * modules define what the exported triples mean, and they never enter a dataset - the runtime
 * loads them only as shapes and axioms for the write gate. A dump without them can be re-read but
 * not re-interpreted: nothing in it says which vocabulary it was written against.</p>
 *
 * <p>Written by hand rather than through a serialiser, because there is nothing to serialise from:
 * the metadata is not a graph in any store. Every IRI and literal goes through
 * {@link SparqlTerms}, whose {@code IRIREF} and {@code STRING_LITERAL2} productions Turtle and
 * TriG share verbatim with SPARQL. An ontology IRI that could not be written safely is left out
 * rather than allowed to produce invalid TriG under a filename that claims a completed export.</p>
 */
public final class ExportMetadata {

    private final ServerVersion serverVersion;
    private final Map<String, String> ontologyVersions;
    private final Clock clock;

    /**
     * @param serverVersion    the build this daemon is running
     * @param ontologyVersions ontology module IRI to {@code owl:versionInfo}, as
     *                         {@code OntologyVersions#onClasspath()} read them; may be empty
     * @param clock            supplies the export timestamp - injected for the same reason
     *                         {@code WriteFunnel}'s is: it is the one value of the envelope that
     *                         does not follow from its inputs
     */
    public ExportMetadata(final ServerVersion serverVersion, final Map<String, String> ontologyVersions,
            final Clock clock) {
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
        this.ontologyVersions = new LinkedHashMap<>(Objects.requireNonNull(ontologyVersions, "ontologyVersions"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Renders one export's envelope as a TriG graph block.
     *
     * <p>Fully qualified IRIs throughout, no prefix declarations: the block is appended to text a
     * different writer produced, and a prefix it did not declare would be the one way this
     * addition could invalidate an otherwise good dump.</p>
     *
     * @return the {@code <graph> { ... }} block, newline-terminated
     */
    public String trigBlock() {
        final String exportIri = ExportMetadataVocabulary.EXPORT_IRI_BASE + UUID.randomUUID();
        final List<String> modules = writableModuleIris();

        final StringBuilder trig = new StringBuilder(512);
        trig.append(System.lineSeparator())
                .append(SparqlTerms.iriRef(ExportMetadataVocabulary.EXPORT_METADATA_GRAPH))
                .append(" {").append(System.lineSeparator());

        appendExportActivity(trig, exportIri, modules);
        appendServerAgent(trig);
        modules.forEach(module -> appendOntologyModule(trig, module));

        trig.append("}").append(System.lineSeparator());
        return trig.toString();
    }

    private void appendExportActivity(final StringBuilder trig, final String exportIri, final List<String> modules) {
        trig.append("    ").append(SparqlTerms.iriRef(exportIri)).append(System.lineSeparator());
        appendPredicate(trig, ExportMetadataVocabulary.RDF_TYPE,
                SparqlTerms.iriRef(ExportMetadataVocabulary.PROV_ACTIVITY), " ;");
        appendPredicate(trig, ExportMetadataVocabulary.PROV_ENDED_AT_TIME,
                dateTime(Instant.now(clock)), " ;");
        appendPredicate(trig, ExportMetadataVocabulary.PROV_WAS_ASSOCIATED_WITH,
                SparqlTerms.iriRef(ExportMetadataVocabulary.SERVER_AGENT), modules.isEmpty() ? " ." : " ;");
        if (!modules.isEmpty()) {
            appendPredicate(trig, ExportMetadataVocabulary.PROV_USED,
                    String.join(" , ", modules.stream().map(SparqlTerms::iriRef).toList()), " .");
        }
        trig.append(System.lineSeparator());
    }

    private void appendServerAgent(final StringBuilder trig) {
        trig.append("    ").append(SparqlTerms.iriRef(ExportMetadataVocabulary.SERVER_AGENT))
                .append(System.lineSeparator());
        appendPredicate(trig, ExportMetadataVocabulary.RDF_TYPE,
                SparqlTerms.iriRef(ExportMetadataVocabulary.PROV_SOFTWARE_AGENT), " ;");
        // The build time is omitted rather than defaulted when no build-info.properties was on the
        // classpath: an invented timestamp would be indistinguishable from a real one.
        final boolean hasBuildTime = serverVersion.buildTime().isPresent();
        appendPredicate(trig, ExportMetadataVocabulary.OWL_VERSION_INFO,
                literal(serverVersion.version()), hasBuildTime ? " ;" : " .");
        serverVersion.buildTime().ifPresent(buildTime ->
                appendPredicate(trig, ExportMetadataVocabulary.DCTERMS_CREATED, dateTime(buildTime), " ."));
        trig.append(System.lineSeparator());
    }

    private void appendOntologyModule(final StringBuilder trig, final String moduleIri) {
        trig.append("    ").append(SparqlTerms.iriRef(moduleIri)).append(System.lineSeparator());
        appendPredicate(trig, ExportMetadataVocabulary.RDF_TYPE,
                SparqlTerms.iriRef(ExportMetadataVocabulary.OWL_ONTOLOGY), " ;");
        appendPredicate(trig, ExportMetadataVocabulary.OWL_VERSION_INFO,
                literal(ontologyVersions.get(moduleIri)), " .");
        trig.append(System.lineSeparator());
    }

    private static void appendPredicate(final StringBuilder trig, final String predicate, final String object,
            final String terminator) {
        trig.append("        ").append(SparqlTerms.iriRef(predicate)).append(' ').append(object)
                .append(terminator).append(System.lineSeparator());
    }

    /**
     * Only modules whose IRI can appear inside a TriG {@code IRIREF}. Nothing shipped today comes
     * close to failing this; the filter is here so a future module cannot turn a well-formed dump
     * into an unparseable one.
     */
    private List<String> writableModuleIris() {
        final List<String> writable = new ArrayList<>();
        ontologyVersions.forEach((iri, version) -> {
            if (SparqlTerms.isValidIriReference(iri)) {
                writable.add(iri);
            }
        });
        return writable;
    }

    private static String literal(final String lexical) {
        return "\"" + SparqlTerms.escape(lexical) + "\"";
    }

    private static String dateTime(final Instant instant) {
        return literal(instant.truncatedTo(ChronoUnit.SECONDS).toString())
                + "^^" + SparqlTerms.iriRef(ExportMetadataVocabulary.XSD_DATE_TIME);
    }
}
