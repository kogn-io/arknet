// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.application.port.in.FieldDescription;
import de.hauschel.arknet.resource.application.port.in.HistoryReading;
import de.hauschel.arknet.resource.application.port.in.ReadResources;
import de.hauschel.arknet.resource.application.port.in.RenderedField;
import de.hauschel.arknet.resource.application.port.in.RenderedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceHistory;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;
import de.hauschel.arknet.resource.application.port.out.ResourceReader;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.ResourceSchema.OwnedField;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * Reads resources of any type and renders them from shapes and ontology alone (ADR-69): no type,
 * field or namespace of a bounded context is named here.
 *
 * <ul>
 * <li>The fields of a resource are those {@code describe} lists for its types, in the same order
 * ({@link DescribeService#FIELD_ORDER}); a field without a value is left out.</li>
 * <li>An owned field ({@code arkres:childKind}) renders its children in place, ordered by their
 * {@code arkres:orderedBy} value.</li>
 * <li>An IRI value renders as handle and label: a resource by its business code and the value of its
 * label field ({@code arkres:labelField}) - a type without one, or a node whose types reach two,
 * falls back to type label and handle - an ontology term by its {@code rdfs:label}.</li>
 * <li>A multilingual field ({@code sh:uniqueLang}) shows its value in the display language, marked
 * when it fell back to another, or every language on request.</li>
 * <li>An edge pointing at the resource whose property the ontology declares
 * {@code owl:SymmetricProperty}, or {@code owl:inverseOf} another, is merged into the resource's own
 * field; every other one is listed as incoming, from the resource owning its subject.</li>
 * <li>Any other node of the resource's slice - a fragment or blank node an edge of the resource
 * reaches without an owned field - renders in place under that edge, without a position.</li>
 * <li>Statements outside the shapes are kept as further statements, so a record from before a shape
 * change stays readable.</li>
 * </ul>
 *
 * <p>Every read of one call runs in one read transaction of {@link ResourceReader}.</p>
 */
public final class ReadService implements ReadResources, ResourceHistory {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final IRI INVERSE_OF = RDF_FACTORY.createIRI(OWL + "inverseOf");
    private static final IRI SYMMETRIC_PROPERTY = RDF_FACTORY.createIRI(OWL + "SymmetricProperty");
    private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
    private static final char FRAGMENT = '#';
    private static final String BLANK_NODE_PREFIX = "_:";

    private final ResourceReader reader;
    private final ResourceSchema schema;
    private final ReadableGraph ontology;
    private final PrefixBindings prefixes;
    private final DescribeService describer;
    private final DisplayLocale defaultLocale;
    private final Set<IRI> symmetric;
    private final Map<IRI, IRI> inverse;
    private final Map<String, List<FieldDescription>> fieldsByTypeAndLanguage = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param reader        the store
     * @param schema        the rule data of the shapes: resource types, label, status and owned fields
     * @param ontology      the model ontologies: labels, class hierarchy, inverse and symmetric
     *                      properties
     * @param prefixes      the prefixes of the shipped files, for handles and prefixed names
     * @param describer     the description of the types, whose fields and order a reading shares
     * @param defaultLocale the display language when a call names none
     */
    public ReadService(final ResourceReader reader, final ResourceSchema schema, final ReadableGraph ontology,
            final PrefixBindings prefixes, final DescribeService describer, final DisplayLocale defaultLocale) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.ontology = Objects.requireNonNull(ontology, "ontology");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.describer = Objects.requireNonNull(describer, "describer");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.symmetric = ontology.stream(null, VocabRdf.TYPE, SYMMETRIC_PROPERTY)
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .collect(Collectors.toUnmodifiableSet());
        Map<IRI, IRI> inverses = new HashMap<>();
        ontology.stream(null, INVERSE_OF, null).forEach(triple -> {
            if (triple.getSubject() instanceof IRI one && triple.getObject() instanceof IRI other) {
                inverses.putIfAbsent(one, other);
                inverses.putIfAbsent(other, one);
            }
        });
        this.inverse = Map.copyOf(inverses);
    }

    @Override
    public List<ResourceReading> read(final ProjectId projectId, final List<String> handles,
            final String displayLocale, final boolean allLanguages) {
        Objects.requireNonNull(projectId, "projectId");
        List<String> requested = List.copyOf(Objects.requireNonNull(handles, "handles"));
        DisplayLocale locale = defaultLocale.withRequestedOverride(displayLocale);
        return reader.read(projectId, snapshot -> {
            Rendering rendering = new Rendering(snapshot, locale, allLanguages);
            return requested.stream().map(rendering::read).toList();
        });
    }

    @Override
    public HistoryReading history(final ProjectId projectId, final String handle, final String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(handle, "handle");
        DisplayLocale locale = defaultLocale.withRequestedOverride(displayLocale);
        return reader.read(projectId, snapshot -> {
            Rendering rendering = new Rendering(snapshot, locale, false);
            Resolution resolution = rendering.resolve(handle);
            IRI iri = resolution.iri().orElseThrow(() -> new IllegalArgumentException(resolution.problem()));
            List<HistoryReading.Entry> entries = snapshot.history(iri).stream()
                    .map(entry -> new HistoryReading.Entry(entry.sequence(), entry.tombstone(), entry.created(),
                            entry.version(), entry.comment()))
                    .toList();
            Optional<ResourceReading.Deleted> deleted = snapshot.slice(iri).isEmpty()
                    ? snapshot.deletion(iri).map(deletion -> rendering.deleted(handle, deletion))
                    : Optional.empty();
            return new HistoryReading(handle, iri.getIRIString(), snapshot.publishState(iri), entries, deleted);
        });
    }

    /** A handle resolved to an IRI, or why it resolves to none. */
    private record Resolution(Optional<IRI> iri, String problem) {

        static Resolution of(final IRI iri) {
            return new Resolution(Optional.of(iri), "");
        }

        static Resolution failed(final String problem) {
            return new Resolution(Optional.empty(), problem);
        }
    }

    /** The reads and caches of one call. */
    private final class Rendering {

        private final ResourceReader.Snapshot snapshot;
        private final DisplayLocale locale;
        private final boolean allLanguages;
        private final Map<IRI, RenderedValue.Reference> references = new HashMap<>();
        private final Set<BlankNodeOrIRI> inProgress = new HashSet<>();
        private final Map<ReadableGraph, Set<RDFTerm>> ownedNodesByGraph = new IdentityHashMap<>();

        private Rendering(final ResourceReader.Snapshot snapshot, final DisplayLocale locale,
                final boolean allLanguages) {
            this.snapshot = snapshot;
            this.locale = locale;
            this.allLanguages = allLanguages;
        }

        ResourceReading read(final String handle) {
            Resolution resolution = resolve(handle);
            if (resolution.iri().isEmpty()) {
                return new ResourceReading.Unresolved(handle, resolution.problem());
            }
            IRI iri = resolution.iri().get();
            ReadableGraph slice = snapshot.slice(iri);
            if (!slice.isEmpty()) {
                return new ResourceReading.Found(handle, resource(iri, slice));
            }
            return snapshot.deletion(iri).<ResourceReading>map(deletion -> deleted(handle, deletion))
                    .orElseGet(() -> new ResourceReading.Unresolved(handle, "no resource " + iri.getIRIString()));
        }

        Resolution resolve(final String written) {
            String handle = written.strip();
            if (handle.isEmpty()) {
                return Resolution.failed("an empty handle names no resource; pass a business code such as"
                        + " FR-1, a full IRI or a prefixed name");
            }
            if (handle.startsWith(BLANK_NODE_PREFIX)) {
                return Resolution.failed("a blank node \"" + handle + "\" has no handle that stays the same"
                        + " between two reads; pass the business code or IRI of the resource holding it");
            }
            if (handle.indexOf(':') < 0) {
                return byCode(handle);
            }
            Optional<String> expanded = prefixes.expand(handle);
            if (expanded.isEmpty()) {
                return Resolution.failed("the prefix of \"" + handle + "\" is not bound; the prefixes are "
                        + prefixes.byPrefix().keySet().stream().sorted().toList()
                        + ". Pass a full IRI or a business code instead");
            }
            String iri = expanded.get();
            int fragment = iri.indexOf(FRAGMENT);
            if (fragment >= 0 && !snapshot.slice(RDF_FACTORY.createIRI(iri.substring(0, fragment))).isEmpty()) {
                return Resolution.failed("<" + iri + "> is part of <" + iri.substring(0, fragment)
                        + ">; read that resource, it renders the nodes of its slice in place");
            }
            return Resolution.of(RDF_FACTORY.createIRI(iri));
        }

        private Resolution byCode(final String code) {
            List<IRI> living = snapshot.withCode(code);
            if (living.size() > 1) {
                return Resolution.failed("the code " + code + " names several resources: "
                        + living.stream().map(IRI::getIRIString).sorted().toList()
                        + "; pass the IRI of the one you mean");
            }
            if (living.size() == 1) {
                return Resolution.of(living.get(0));
            }
            return snapshot.deletedWithCode(code).map(Resolution::of)
                    .orElseGet(() -> Resolution.failed("no resource carries the code " + code));
        }

        private RenderedResource resource(final IRI iri, final ReadableGraph slice) {
            Set<IRI> types = typesOf(iri, slice);
            String handle = handleOf(iri, slice);
            Map<IRI, Set<RDFTerm>> merged = new LinkedHashMap<>();
            Map<IRI, Set<RDFTerm>> incoming = new LinkedHashMap<>();
            snapshot.pointingAt(iri).stream().forEach(triple -> {
                RDFTerm from = owner(triple.getSubject());
                if (from.equals(iri)) {
                    return;
                }
                IRI property = triple.getPredicate();
                if (symmetric.contains(property)) {
                    merged.computeIfAbsent(property, any -> new LinkedHashSet<>()).add(from);
                } else if (inverse.containsKey(property)) {
                    merged.computeIfAbsent(inverse.get(property), any -> new LinkedHashSet<>()).add(from);
                } else {
                    incoming.computeIfAbsent(property, any -> new LinkedHashSet<>()).add(from);
                }
            });
            Fields fields = fields(iri, slice, types, merged, Set.of());
            Optional<RenderedValue.Text> label = labelField(types)
                    .flatMap(field -> fields.valuesOf(field).stream()
                            .filter(RenderedValue.Text.class::isInstance)
                            .map(RenderedValue.Text.class::cast)
                            .findFirst());
            Optional<RenderedValue.Reference> status = statusField(types)
                    .flatMap(field -> fields.valuesOf(field).stream()
                            .filter(RenderedValue.Reference.class::isInstance)
                            .map(RenderedValue.Reference.class::cast)
                            .findFirst());
            List<ResourceReader.HistoryEntry> history = snapshot.history(iri);
            Optional<String> created = history.stream().findFirst().map(ResourceReader.HistoryEntry::created);
            Optional<String> lastChanged = history.isEmpty() ? Optional.empty()
                    : Optional.of(history.get(history.size() - 1).created());
            List<RenderedField> incomingFields = incoming.entrySet().stream()
                    .map(entry -> field(entry.getKey(), label(entry.getKey()), entry.getValue().stream()
                            .map(this::value).sorted(VALUE_ORDER).toList()))
                    .sorted(Comparator.comparing(RenderedField::name))
                    .toList();
            return new RenderedResource(iri.getIRIString(), handle, typeLabel(types),
                    types.stream().map(this::reference).sorted(Comparator.comparing(RenderedValue.Reference::handle))
                            .toList(),
                    label, status, created, lastChanged, fields.shaped(), incomingFields, fields.others());
        }

        ResourceReading.Deleted deleted(final String handle, final ResourceReader.Deletion deletion) {
            ReadableGraph last = deletion.lastVersion();
            Fields fields = fields(deletion.resource(), last, typesOf(deletion.resource(), last), Map.of(),
                    Set.of());
            return new ResourceReading.Deleted(handle, deletion.resource().getIRIString(), deletion.deletedAt(),
                    deletion.comment(), deletion.code(),
                    Stream.concat(fields.shaped().stream(), fields.others().stream()).toList());
        }

        /**
         * The fields of {@code subject} in {@code graph}: those of its shapes in describe order, and
         * the further statements.
         */
        private Fields fields(final BlankNodeOrIRI subject, final ReadableGraph graph, final Set<IRI> types,
                final Map<IRI, Set<RDFTerm>> merged, final Set<IRI> skipped) {
            inProgress.add(subject);
            try {
                return fieldsOf(subject, graph, types, merged, skipped);
            } finally {
                inProgress.remove(subject);
            }
        }

        private Fields fieldsOf(final BlankNodeOrIRI subject, final ReadableGraph graph, final Set<IRI> types,
                final Map<IRI, Set<RDFTerm>> merged, final Set<IRI> skipped) {
            Map<IRI, Set<RDFTerm>> values = new LinkedHashMap<>();
            graph.stream(subject, null, null)
                    .filter(triple -> !VocabRdf.TYPE.equals(triple.getPredicate()))
                    .filter(triple -> !skipped.contains(triple.getPredicate()))
                    .forEach(triple -> values.computeIfAbsent(triple.getPredicate(), any -> new LinkedHashSet<>())
                            .add(triple.getObject()));
            merged.forEach((property, from) -> values.computeIfAbsent(property, any -> new LinkedHashSet<>())
                    .addAll(from));
            Set<IRI> multilingual = schema.multilingualFields(types);
            List<RenderedField> shaped = new ArrayList<>();
            for (FieldDescription description : descriptions(types)) {
                IRI property = RDF_FACTORY.createIRI(description.iri());
                Set<RDFTerm> held = values.remove(property);
                if (held == null || held.isEmpty()) {
                    continue;
                }
                shaped.add(field(property, description.label(),
                        values(property, held, graph, multilingual.contains(property))));
            }
            List<RenderedField> others = values.entrySet().stream()
                    .map(entry -> field(entry.getKey(), label(entry.getKey()),
                            values(entry.getKey(), entry.getValue(), graph, false)))
                    .sorted(Comparator.comparing(RenderedField::name))
                    .toList();
            return new Fields(shaped, others);
        }

        private List<RenderedValue> values(final IRI property, final Set<RDFTerm> held, final ReadableGraph graph,
                final boolean multilingual) {
            Optional<OwnedField> owned = schema.ownedField(property);
            if (owned.isPresent()) {
                return held.stream()
                        .filter(BlankNodeOrIRI.class::isInstance)
                        .map(child -> child((BlankNodeOrIRI) child, graph, owned.get()))
                        .sorted(CHILD_ORDER)
                        .map(RenderedValue.class::cast)
                        .toList();
            }
            List<RenderedValue> rendered = new ArrayList<>();
            List<Literal> literals = held.stream().filter(Literal.class::isInstance).map(Literal.class::cast)
                    .toList();
            if (multilingual && !allLanguages && !literals.isEmpty()) {
                rendered.add(selected(literals));
            } else {
                literals.stream().map(literal -> text(literal, false)).forEach(rendered::add);
            }
            held.stream().filter(term -> !(term instanceof Literal))
                    .map(term -> inSlice(term, graph) ? inPlace((BlankNodeOrIRI) term, graph) : value(term))
                    .forEach(rendered::add);
            return rendered.stream().sorted(VALUE_ORDER).toList();
        }

        /**
         * Whether {@code term} is a further node of the graph being rendered - a fragment or blank node of
         * the resource's slice that no owned field reaches - and not already being rendered higher up.
         */
        private boolean inSlice(final RDFTerm term, final ReadableGraph graph) {
            return term instanceof BlankNodeOrIRI node && !inProgress.contains(node)
                    && !ownedNodes(graph).contains(node)
                    && graph.stream(node, null, null).findAny().isPresent();
        }

        /**
         * The nodes of {@code graph} an owned field holds, at any depth: they render in order under that
         * field, so an edge reaching one of them otherwise renders it as a reference, not a second time.
         */
        private Set<RDFTerm> ownedNodes(final ReadableGraph graph) {
            return ownedNodesByGraph.computeIfAbsent(graph, any -> graph.stream()
                    .filter(triple -> schema.ownedField(triple.getPredicate()).isPresent())
                    .map(Triple::getObject)
                    .collect(Collectors.toUnmodifiableSet()));
        }

        /** A node of the slice no owned field reaches, rendered in place under the edge pointing at it. */
        private RenderedValue.Child inPlace(final BlankNodeOrIRI node, final ReadableGraph graph) {
            Fields fields = fields(node, graph, typesOf(node, graph), Map.of(), Set.of());
            return new RenderedValue.Child(term(node), Optional.empty(), fields.shaped(), fields.others());
        }

        private RenderedValue.Child child(final BlankNodeOrIRI child, final ReadableGraph graph,
                final OwnedField owned) {
            Set<IRI> types = new LinkedHashSet<>(typesOf(child, graph));
            types.add(owned.childClass());
            Optional<Integer> position = graph.stream(child, owned.orderedBy(), null)
                    .map(Triple::getObject)
                    .filter(Literal.class::isInstance)
                    .map(term -> parsePosition(((Literal) term).getLexicalForm()))
                    .flatMap(Optional::stream)
                    .findFirst();
            Fields fields = fields(child, graph, types, Map.of(), Set.of(owned.orderedBy()));
            return new RenderedValue.Child(term(child), position, fields.shaped(), fields.others());
        }

        private RenderedValue.Text selected(final List<Literal> literals) {
            List<LocalizedLiteral> candidates = literals.stream()
                    .map(literal -> literal.getLanguageTag()
                            .map(tag -> LocalizedLiteral.tagged(literal.getLexicalForm(), tag))
                            .orElseGet(() -> LocalizedLiteral.untagged(literal.getLexicalForm())))
                    .toList();
            LocalizedLiteral chosen = locale.select(candidates).orElseThrow();
            Literal literal = literals.stream()
                    .filter(candidate -> candidate.getLexicalForm().equals(chosen.value())
                            && Objects.equals(candidate.getLanguageTag().orElse(null), chosen.languageTag()))
                    .findFirst()
                    .orElseThrow();
            return text(literal, !inRequestedLanguage(chosen.languageTag()));
        }

        private boolean inRequestedLanguage(final String tag) {
            return tag != null && Locale.forLanguageTag(tag).getLanguage()
                    .equalsIgnoreCase(locale.requested().getLanguage());
        }

        private RenderedValue.Text text(final Literal literal, final boolean fallback) {
            String datatype = literal.getDatatype() == null ? null : literal.getDatatype().getIRIString();
            Optional<String> shown = literal.getLanguageTag().isPresent() || datatype == null
                    || VocabXsd.STRING.getIRIString().equals(datatype) || LANG_STRING.equals(datatype)
                    ? Optional.empty() : Optional.of(prefixes.abbreviate(datatype));
            return new RenderedValue.Text(literal.getLexicalForm(), literal.getLanguageTag(), shown, fallback);
        }

        private RenderedValue value(final RDFTerm term) {
            if (term instanceof IRI iri) {
                return reference(iri);
            }
            if (term instanceof Literal literal) {
                return text(literal, false);
            }
            String blank = term(term);
            return new RenderedValue.Reference(blank, blank, blank);
        }

        private RenderedValue.Reference reference(final IRI iri) {
            RenderedValue.Reference known = references.get(iri);
            if (known != null) {
                return known;
            }
            RenderedValue.Reference created = newReference(iri);
            references.put(iri, created);
            return created;
        }

        private RenderedValue.Reference newReference(final IRI iri) {
            Optional<String> termLabel = text(ontology, iri, VocabRdfs.LABEL);
            if (termLabel.isPresent()) {
                return new RenderedValue.Reference(iri.getIRIString(), iriHandle(iri), termLabel.get());
            }
            if (iri.getIRIString().indexOf(FRAGMENT) >= 0) {
                return new RenderedValue.Reference(iri.getIRIString(), iriHandle(iri), iriHandle(iri));
            }
            ReadableGraph slice = snapshot.slice(iri);
            if (slice.isEmpty()) {
                return new RenderedValue.Reference(iri.getIRIString(), iriHandle(iri), iriHandle(iri));
            }
            Set<IRI> types = typesOf(iri, slice);
            String handle = handleOf(iri, slice);
            Set<IRI> multilingual = schema.multilingualFields(types);
            String label = labelField(types)
                    .flatMap(field -> {
                        List<Literal> literals = slice.stream(iri, field, null).map(Triple::getObject)
                                .filter(Literal.class::isInstance).map(Literal.class::cast).toList();
                        if (literals.isEmpty()) {
                            return Optional.<String>empty();
                        }
                        return Optional.of(multilingual.contains(field) ? selected(literals).text()
                                : literals.stream().map(Literal::getLexicalForm).sorted().findFirst().orElseThrow());
                    })
                    .orElseGet(() -> typeLabel(types) + " " + handle);
            return new RenderedValue.Reference(iri.getIRIString(), handle, label);
        }

        /**
         * The label field of a node, empty for a type without one - and for a node whose types reach
         * two, which falls back like a type without one instead of failing the whole read.
         */
        private Optional<IRI> labelField(final Set<IRI> types) {
            try {
                return schema.labelField(types);
            } catch (IllegalArgumentException twoLabelFields) {
                return Optional.empty();
            }
        }

        /** The status field of a node, empty for a type without one or a node whose types reach two. */
        private Optional<IRI> statusField(final Set<IRI> types) {
            try {
                return schema.statusField(types).map(ResourceSchema.StatusField::property);
            } catch (SubmitRejectedException twoStatusFields) {
                return Optional.empty();
            }
        }

        private String typeLabel(final Set<IRI> types) {
            Optional<IRI> resourceType;
            try {
                resourceType = schema.resourceTypeOf(types).map(ResourceSchema.ResourceType::type);
            } catch (SubmitRejectedException twoResourceTypes) {
                resourceType = Optional.empty();
            }
            return resourceType.or(() -> types.stream().min(Comparator.comparing(IRI::getIRIString)))
                    .map(this::label)
                    .orElse("Resource");
        }

        private List<FieldDescription> descriptions(final Set<IRI> types) {
            Map<String, FieldDescription> byIri = new LinkedHashMap<>();
            types.stream().sorted(Comparator.comparing(IRI::getIRIString))
                    .flatMap(type -> fieldsOf(type).stream())
                    .forEach(description -> byIri.putIfAbsent(description.iri(), description));
            return byIri.values().stream().sorted(DescribeService.FIELD_ORDER).toList();
        }

        private List<FieldDescription> fieldsOf(final IRI type) {
            return fieldsByTypeAndLanguage.computeIfAbsent(type.getIRIString() + " " + locale.requested()
                    .toLanguageTag(), any -> describer.fields(type, locale));
        }

        private Optional<String> text(final ReadableGraph graph, final BlankNodeOrIRI subject, final IRI property) {
            return ReadService.text(graph, subject, property, locale);
        }

        private String label(final IRI property) {
            return text(ontology, property, VocabRdfs.LABEL).orElseGet(() -> localName(property));
        }
    }

    /** The fields of one subject: those its shapes give it, and the further statements. */
    private record Fields(List<RenderedField> shaped, List<RenderedField> others) {

        List<RenderedValue> valuesOf(final IRI property) {
            return shaped.stream().filter(field -> field.iri().equals(property.getIRIString()))
                    .flatMap(field -> field.values().stream())
                    .toList();
        }
    }

    private static final Comparator<RenderedValue> VALUE_ORDER = Comparator.comparing(ReadService::sortKey);

    private static final Comparator<RenderedValue> CHILD_ORDER = Comparator.comparing(
            (RenderedValue value) -> ((RenderedValue.Child) value).position().orElse(Integer.MAX_VALUE))
            .thenComparing(value -> ((RenderedValue.Child) value).iri());

    private static String sortKey(final RenderedValue value) {
        return switch (value) {
            case RenderedValue.Text text -> "0 " + text.language().orElse("") + " " + text.text();
            case RenderedValue.Reference reference -> "1 " + reference.handle();
            case RenderedValue.Child child -> "2 " + child.iri();
        };
    }

    private RenderedField field(final IRI property, final String label, final List<RenderedValue> values) {
        return new RenderedField(property.getIRIString(), prefixes.abbreviate(property.getIRIString()), label,
                values);
    }

    private static Optional<String> text(final ReadableGraph graph, final BlankNodeOrIRI subject,
            final IRI property, final DisplayLocale locale) {
        List<LocalizedLiteral> candidates = graph.stream(subject, property, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(Literal.class::cast)
                .map(literal -> literal.getLanguageTag()
                        .map(tag -> LocalizedLiteral.tagged(literal.getLexicalForm(), tag))
                        .orElseGet(() -> LocalizedLiteral.untagged(literal.getLexicalForm())))
                .toList();
        return locale.select(candidates).map(LocalizedLiteral::value);
    }

    private static Set<IRI> typesOf(final BlankNodeOrIRI subject, final ReadableGraph graph) {
        return graph.stream(subject, VocabRdf.TYPE, null)
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String handleOf(final IRI iri, final ReadableGraph slice) {
        return slice.stream(iri, VocabDct.IDENTIFIER, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(term -> ((Literal) term).getLexicalForm())
                .sorted()
                .findFirst()
                .orElseGet(() -> iriHandle(iri));
    }

    /** An IRI's handle without a code: its prefixed name if a shipped prefix covers it, else the IRI. */
    private String iriHandle(final IRI iri) {
        String abbreviated = prefixes.abbreviate(iri.getIRIString());
        return abbreviated.startsWith("<") ? iri.getIRIString() : abbreviated;
    }

    /** The resource an edge comes from: the subject itself, or the resource owning a child fragment. */
    private static RDFTerm owner(final BlankNodeOrIRI subject) {
        if (subject instanceof IRI iri) {
            int fragment = iri.getIRIString().indexOf(FRAGMENT);
            return fragment < 0 ? iri : RDF_FACTORY.createIRI(iri.getIRIString().substring(0, fragment));
        }
        return subject;
    }

    private static String term(final RDFTerm term) {
        if (term instanceof IRI iri) {
            return iri.getIRIString();
        }
        if (term instanceof BlankNode blankNode) {
            return BLANK_NODE_PREFIX + blankNode.uniqueReference();
        }
        return term.toString();
    }

    private static Optional<Integer> parsePosition(final String lexical) {
        try {
            return Optional.of(Integer.valueOf(lexical.strip()));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static String localName(final IRI term) {
        String iri = term.getIRIString();
        int cut = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return iri.substring(cut + 1);
    }
}
