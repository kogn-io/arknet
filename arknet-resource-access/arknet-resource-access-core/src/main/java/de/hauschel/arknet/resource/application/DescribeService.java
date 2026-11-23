// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import de.hauschel.arknet.resource.application.port.in.ChangeExample;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.FieldDescription;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Kind;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.OwnedChildren;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Presence;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog.VocabularyTerm;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeSummary;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.ResourceSchema.OwnedField;

/**
 * Describes resource types from the shapes and the ontology alone (ADR-59, ADR-69).
 *
 * <p>A type's fields are the property shapes of every node shape that targets the type or one of
 * its superclasses in the ontology; several shapes on one property merge into one field. Labels and
 * comments come from the ontology, rule texts from the {@code sh:message} of the shapes, each in the
 * display language. Nothing here names a class or a property of the model: the only IRIs it knows
 * are those of SHACL, RDF, RDFS, XSD, Dublin Core's identifier and its own {@code arkres:}.</p>
 *
 * <p>The example of a type creates one resource of it with every required field and one child in
 * every owned field. A required edge to a resource type gets a resource of its own in the same
 * document, so the example runs as it stands against an empty project.</p>
 *
 * <p>The change examples name that resource by its handle, the code it gets in an empty project
 * ({@code <FR-1>}), and run in their order after the create example: an update sets the first text
 * field, or else the first value or choice; a type with owned children adds a second child to its
 * first owned field and removes the first; the delete comes last. A type without a code has no handle;
 * its change examples name a placeholder to replace by the IRI {@code submit} returned.</p>
 */
public final class DescribeService implements DescribeResourceType {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final IRI TARGET_CLASS = sh("targetClass");
    private static final IRI PROPERTY = sh("property");
    private static final IRI PATH = sh("path");
    private static final IRI MIN_COUNT = sh("minCount");
    private static final IRI MAX_COUNT = sh("maxCount");
    private static final IRI DATATYPE = sh("datatype");
    private static final IRI CLASS = sh("class");
    private static final IRI OR = sh("or");
    private static final IRI IN = sh("in");
    private static final IRI UNIQUE_LANG = sh("uniqueLang");
    private static final IRI NODE_KIND = sh("nodeKind");
    private static final IRI KIND_IRI = sh("IRI");
    private static final IRI MESSAGE = sh("message");
    private static final IRI SEVERITY = sh("severity");
    private static final IRI WARNING = sh("Warning");
    private static final IRI SPARQL = sh("sparql");
    private static final IRI SUB_CLASS_OF = RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf");
    private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
    private static final Set<String> RULE_DATA = Set.of(ArkresVocabulary.RESOURCE_TYPE,
            ArkresVocabulary.CODE_PREFIX, ArkresVocabulary.CHILD_KIND, ArkresVocabulary.ORDERED_BY);
    private static final String EXAMPLE_TEXT = "Example text";
    private static final String EXAMPLE_STRING = "example";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String CHANGED_TEXT = "Changed example text";
    private static final String NO_HANDLE = "<IRI-RETURNED-BY-SUBMIT>";
    private static final int FIRST_CODE = 1;
    private static final int ADDED_POSITION = 2;
    private static final int REMOVED_POSITION = 1;

    private final ReadableGraph shapes;
    private final ReadableGraph ontology;
    private final ResourceSchema schema;
    private final PrefixBindings prefixes;
    private final DisplayLocale defaultLocale;

    /**
     * Creates the service.
     *
     * @param shapes        the shapes the write gate validates against, with their rule data
     * @param ontology      the ontologies of the model and of {@code arkres:}: class hierarchy, labels, comments
     * @param schema        the rule data read off {@code shapes}
     * @param prefixes      the prefixes the shipped files bind
     * @param defaultLocale the display language when a call names none
     */
    public DescribeService(final ReadableGraph shapes, final ReadableGraph ontology, final ResourceSchema schema,
            final PrefixBindings prefixes, final DisplayLocale defaultLocale) {
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.ontology = Objects.requireNonNull(ontology, "ontology");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
    }

    @Override
    public ResourceCatalog catalog(final String displayLocale) {
        DisplayLocale locale = defaultLocale.withRequestedOverride(displayLocale);
        List<ResourceTypeSummary> types = resourceTypes().stream().map(type -> summary(type, locale)).toList();
        List<VocabularyTerm> vocabulary = ontology.stream(null, VocabRdfs.LABEL, null)
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .filter(term -> term.getIRIString().startsWith(ArkresVocabulary.NAMESPACE))
                .filter(term -> !RULE_DATA.contains(term.getIRIString()))
                .distinct()
                .sorted(Comparator.comparing(IRI::getIRIString))
                .map(term -> new VocabularyTerm(prefixes.abbreviate(term.getIRIString()), label(term, locale),
                        comment(term, locale)))
                .toList();
        String names = types.stream().map(ResourceTypeSummary::name).collect(Collectors.joining(" "))
                + " " + vocabulary.stream().map(VocabularyTerm::name).collect(Collectors.joining(" "));
        return new ResourceCatalog(types, vocabulary, prefixes.usedIn(names).declarations());
    }

    @Override
    public ResourceTypeDescription describe(final String type, final String displayLocale) {
        Objects.requireNonNull(type, "type");
        IRI iri = resolve(type);
        DisplayLocale locale = defaultLocale.withRequestedOverride(displayLocale);
        ResourceTypeSummary summary = summary(iri, locale);
        List<FieldDescription> fields = fields(iri, locale);
        List<String> rules = rules(iri, locale);
        String example = example(iri, locale);
        List<ChangeExample> changeExamples = changeExamples(iri, fields, locale);
        String rendered = Stream.of(Stream.of(summary.name(), summary.comment(), example),
                changeExamples.stream().map(ChangeExample::document), rules.stream(),
                fields.stream().flatMap(DescribeService::names))
                .flatMap(names -> names)
                .collect(Collectors.joining("\n"));
        return new ResourceTypeDescription(summary, fields, rules, example, changeExamples,
                prefixes.usedIn(rendered).declarations());
    }

    private static Stream<String> names(final FieldDescription field) {
        return Stream.of(Stream.of(field.name(), field.comment()), field.datatype().stream(),
                field.valueTypes().stream(), field.allowedValues().stream(), field.rules().stream(),
                field.children().stream().flatMap(children -> Stream.concat(Stream.of(children.orderedBy()),
                        children.fields().stream().flatMap(DescribeService::names))))
                .flatMap(names -> names);
    }

    private List<IRI> resourceTypes() {
        return schema.resourceTypes().stream()
                .map(RDF_FACTORY::createIRI)
                .sorted(Comparator.comparing(type -> prefixes.abbreviate(type.getIRIString())))
                .toList();
    }

    private IRI resolve(final String type) {
        Optional<IRI> resolved = prefixes.expand(type)
                .filter(schema.resourceTypes()::contains)
                .map(RDF_FACTORY::createIRI);
        return resolved.orElseThrow(() -> new IllegalArgumentException("\"" + type + "\" is no resource type;"
                + " the resource types are " + resourceTypes().stream()
                        .map(known -> prefixes.abbreviate(known.getIRIString())).toList()
                + ". Name a type by its prefixed name or its full IRI.\n" + prefixes.declarations()));
    }

    private ResourceTypeSummary summary(final IRI type, final DisplayLocale locale) {
        return new ResourceTypeSummary(type.getIRIString(), prefixes.abbreviate(type.getIRIString()),
                label(type, locale), comment(type, locale), codePrefix(type));
    }

    private Optional<String> codePrefix(final IRI type) {
        return schema.resourceTypeOf(Set.of(type)).flatMap(ResourceSchema.ResourceType::codePrefix);
    }

    private List<FieldDescription> fields(final IRI type, final DisplayLocale locale) {
        boolean coded = codePrefix(type).isPresent();
        Map<IRI, List<BlankNodeOrIRI>> shapesByPath = new LinkedHashMap<>();
        nodeShapes(type).forEach(node -> shapes.stream(node, PROPERTY, null)
                .map(Triple::getObject)
                .filter(BlankNodeOrIRI.class::isInstance)
                .map(BlankNodeOrIRI.class::cast)
                .forEach(property -> single(shapes, property, PATH).filter(IRI.class::isInstance)
                        .map(IRI.class::cast)
                        .ifPresent(path -> shapesByPath.computeIfAbsent(path, any -> new ArrayList<>())
                                .add(property))));
        return shapesByPath.entrySet().stream()
                .map(entry -> field(entry.getKey(), entry.getValue(), coded, locale))
                .sorted(Comparator.comparing(FieldDescription::presence).thenComparing(FieldDescription::name))
                .toList();
    }

    private FieldDescription field(final IRI path, final List<BlankNodeOrIRI> propertyShapes, final boolean coded,
            final DisplayLocale locale) {
        int required = 0;
        int recommended = 0;
        Optional<Integer> maxCount = Optional.empty();
        for (BlankNodeOrIRI shape : propertyShapes) {
            boolean warning = single(shapes, shape, SEVERITY).filter(WARNING::equals).isPresent();
            int min = integer(shape, MIN_COUNT).orElse(0);
            if (warning) {
                recommended = Math.max(recommended, min);
                continue;
            }
            required = Math.max(required, min);
            Optional<Integer> max = integer(shape, MAX_COUNT);
            if (max.isPresent()) {
                maxCount = Optional.of(maxCount.map(earlier -> Math.min(earlier, max.get())).orElse(max.get()));
            }
        }
        Presence presence = required > 0 ? Presence.REQUIRED
                : recommended > 0 ? Presence.RECOMMENDED : Presence.OPTIONAL;
        Optional<String> datatype = values(propertyShapes, DATATYPE).filter(IRI.class::isInstance)
                .map(term -> ((IRI) term).getIRIString()).findFirst();
        List<String> valueTypes = Stream.concat(values(propertyShapes, CLASS),
                values(propertyShapes, OR).flatMap(this::list)
                        .filter(BlankNodeOrIRI.class::isInstance)
                        .flatMap(alternative -> shapes.stream((BlankNodeOrIRI) alternative, CLASS, null))
                        .map(Triple::getObject))
                .filter(IRI.class::isInstance)
                .map(term -> prefixes.abbreviate(((IRI) term).getIRIString()))
                .distinct()
                .toList();
        List<String> allowed = values(propertyShapes, IN).flatMap(this::list).map(this::turtle).distinct().toList();
        boolean onePerLanguage = values(propertyShapes, UNIQUE_LANG).anyMatch(DescribeService::isTrue);
        boolean iriValued = values(propertyShapes, NODE_KIND).anyMatch(KIND_IRI::equals);
        Optional<OwnedField> owned = schema.ownedField(path);
        Kind kind = owned.isPresent() ? Kind.CHILDREN
                : !allowed.isEmpty() ? Kind.CHOICE
                : !valueTypes.isEmpty() || iriValued ? Kind.REFERENCE
                : onePerLanguage || datatype.filter(LANG_STRING::equals).isPresent() ? Kind.TEXT
                : Kind.VALUE;
        List<String> rules = propertyShapes.stream()
                .map(shape -> text(shapes, shape, MESSAGE, locale))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        Optional<OwnedChildren> children = owned.map(field -> new OwnedChildren(field.childKind(),
                prefixes.abbreviate(field.orderedBy().getIRIString()), fields(field.childClass(), locale)));
        return new FieldDescription(path.getIRIString(), prefixes.abbreviate(path.getIRIString()),
                label(path, locale), comment(path, locale), kind, presence, maxCount,
                datatype.map(prefixes::abbreviate), valueTypes, allowed, onePerLanguage,
                coded && VocabDct.IDENTIFIER.equals(path), rules, children);
    }

    private List<String> rules(final IRI type, final DisplayLocale locale) {
        return nodeShapes(type).stream()
                .flatMap(node -> Stream.concat(Stream.of(node),
                        shapes.stream(node, SPARQL, null).map(Triple::getObject)
                                .filter(BlankNodeOrIRI.class::isInstance)
                                .map(BlankNodeOrIRI.class::cast)))
                .map(node -> text(shapes, node, MESSAGE, locale))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private List<BlankNodeOrIRI> nodeShapes(final IRI type) {
        return superclasses(type).stream()
                .flatMap(superclass -> shapes.stream(null, TARGET_CLASS, superclass).map(Triple::getSubject))
                .distinct()
                .toList();
    }

    private Set<IRI> superclasses(final IRI type) {
        Set<IRI> seen = new LinkedHashSet<>();
        Deque<IRI> open = new ArrayDeque<>(List.of(type));
        while (!open.isEmpty()) {
            IRI next = open.pop();
            if (seen.add(next)) {
                ontology.stream(next, SUB_CLASS_OF, null).map(Triple::getObject)
                        .filter(IRI.class::isInstance)
                        .map(IRI.class::cast)
                        .forEach(open::push);
            }
        }
        return seen;
    }

    // --- example -------------------------------------------------------------

    private String example(final IRI type, final DisplayLocale locale) {
        ExampleWriter writer = new ExampleWriter(language(locale), locale);
        writer.create(type);
        String body = writer.text();
        return prefixes.usedIn(body).declarations() + body;
    }

    private static String language(final DisplayLocale locale) {
        return Optional.ofNullable(locale.requested()).map(Locale::getLanguage)
                .filter(tag -> !tag.isBlank()).orElse(DEFAULT_LANGUAGE);
    }

    private List<ChangeExample> changeExamples(final IRI type, final List<FieldDescription> fields,
            final DisplayLocale locale) {
        String language = language(locale);
        Optional<String> code = codePrefix(type).map(prefix -> prefix + FIRST_CODE);
        String object = code.map(handle -> "<" + handle + ">").orElse(NO_HANDLE);
        String note = code.map(handle -> "# " + handle + " is the resource the create example makes in an empty"
                + " project; name your own by its code.\n")
                .orElse("# This type has no code: replace " + NO_HANDLE + " by the IRI submit returned for it.\n");
        List<ChangeExample> examples = new ArrayList<>();
        updateValue(fields, language).ifPresent(set -> examples.add(change(ArkresVocabulary.UPDATE, note,
                activity(ArkresVocabulary.UPDATE, object) + " ;\n    " + abbreviated(ArkresVocabulary.SET)
                        + " [ " + set + " ] .\n")));
        fields.stream().filter(field -> field.kind() == Kind.CHILDREN).findFirst().ifPresent(owned -> {
            ExampleWriter writer = new ExampleWriter(language, locale);
            String child = writer.child(owned.children().orElseThrow(), ADDED_POSITION);
            examples.add(change(ArkresVocabulary.ADD_TO, note, writer.text()
                    + activity(ArkresVocabulary.ADD_TO, object) + " ;\n    " + abbreviated(ArkresVocabulary.FIELD)
                    + " " + owned.name() + " ;\n    " + abbreviated(ArkresVocabulary.VALUE) + " " + child + " .\n"));
            examples.add(change(ArkresVocabulary.REMOVE_FROM, note, activity(ArkresVocabulary.REMOVE_FROM, object)
                    + " ;\n    " + abbreviated(ArkresVocabulary.FIELD) + " " + owned.name() + " ;\n    "
                    + abbreviated(ArkresVocabulary.POSITION) + " " + REMOVED_POSITION + " .\n"));
        });
        examples.add(change(ArkresVocabulary.DELETE, note, activity(ArkresVocabulary.DELETE, object) + " .\n"));
        return examples;
    }

    private Optional<String> updateValue(final List<FieldDescription> fields, final String language) {
        Optional<FieldDescription> text = fields.stream()
                .filter(field -> !field.assigned() && field.kind() == Kind.TEXT).findFirst();
        if (text.isPresent()) {
            return text.map(field -> field.name() + " \"" + CHANGED_TEXT + "\"@" + language);
        }
        return fields.stream()
                .filter(field -> !field.assigned() && (field.kind() == Kind.VALUE || field.kind() == Kind.CHOICE))
                .findFirst()
                .map(field -> field.name() + " " + (field.kind() == Kind.VALUE ? literal(field.datatype())
                        : field.allowedValues().get(field.allowedValues().size() - 1)));
    }

    private ChangeExample change(final String activity, final String note, final String body) {
        return new ChangeExample(abbreviated(activity), prefixes.usedIn(body).declarations() + note + body);
    }

    private String activity(final String activity, final String object) {
        return "[] a " + abbreviated(activity) + " ; " + abbreviated(ArkresVocabulary.OBJECT) + " " + object;
    }

    private String abbreviated(final String iri) {
        return prefixes.abbreviate(iri);
    }

    /**
     * Writes the creates of one example document, one per resource it needs.
     */
    private final class ExampleWriter {

        private static final int MAX_RESOURCES = 4;

        private final String language;
        private final DisplayLocale locale;
        private final Map<IRI, String> created = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();

        private ExampleWriter(final String language, final DisplayLocale locale) {
            this.language = language;
            this.locale = locale;
        }

        private String create(final IRI type) {
            String existing = created.get(type);
            if (existing != null) {
                return existing;
            }
            String node = "_:" + localName(type).toLowerCase(Locale.ROOT) + created.size();
            created.put(type, node);
            List<String> lines = new ArrayList<>();
            for (FieldDescription field : fields(type, locale)) {
                if (field.assigned() || field.presence() != Presence.REQUIRED && field.kind() != Kind.CHILDREN) {
                    continue;
                }
                value(field).ifPresent(value -> lines.add(field.name() + " " + value));
            }
            StringBuilder resource = new StringBuilder()
                    .append("[] a ").append(prefixes.abbreviate(ArkresVocabulary.CREATE)).append(" ; ")
                    .append(prefixes.abbreviate(ArkresVocabulary.OBJECT)).append(' ').append(node).append(" .\n")
                    .append(node).append(" a ").append(prefixes.abbreviate(type.getIRIString()));
            lines.forEach(line -> resource.append(" ;\n    ").append(line));
            text.append(resource).append(" .\n");
            return node;
        }

        private Optional<String> value(final FieldDescription field) {
            return switch (field.kind()) {
                case TEXT -> Optional.of("\"" + EXAMPLE_TEXT + "\"@" + language);
                case VALUE -> Optional.of(literal(field.datatype()));
                case CHOICE -> field.allowedValues().stream().findFirst();
                case REFERENCE -> reference(field);
                case CHILDREN -> field.children().map(children -> child(children, 1));
            };
        }

        private Optional<String> reference(final FieldDescription field) {
            return field.valueTypes().stream()
                    .map(prefixes::expand)
                    .flatMap(Optional::stream)
                    .filter(schema.resourceTypes()::contains)
                    .findFirst()
                    .filter(target -> created.containsKey(RDF_FACTORY.createIRI(target))
                            || created.size() < MAX_RESOURCES)
                    .map(target -> create(RDF_FACTORY.createIRI(target)));
        }

        private String child(final OwnedChildren children, final int position) {
            List<String> lines = new ArrayList<>();
            lines.add(children.orderedBy() + " " + position);
            for (FieldDescription field : children.fields()) {
                if (field.name().equals(children.orderedBy()) || field.presence() != Presence.REQUIRED) {
                    continue;
                }
                value(field).ifPresent(value -> lines.add(field.name() + " " + value));
            }
            return "[ " + String.join(" ; ", lines) + " ]";
        }

        private String text() {
            return text.toString();
        }
    }

    private String literal(final Optional<String> datatype) {
        Optional<String> iri = datatype.flatMap(prefixes::expand);
        if (iri.isEmpty() || iri.get().equals(VocabXsd.STRING.getIRIString())) {
            return "\"" + EXAMPLE_STRING + "\"";
        }
        String dt = iri.get();
        if (dt.equals(VocabXsd.INTEGER.getIRIString()) || dt.equals(VocabXsd.NON_NEGATIVE_INTEGER.getIRIString())) {
            return "1";
        }
        if (dt.equals(VocabXsd.BOOLEAN.getIRIString())) {
            return "true";
        }
        if (dt.equals(VocabXsd.DECIMAL.getIRIString())) {
            return "1.0";
        }
        if (dt.equals(VocabXsd.DATE.getIRIString())) {
            return "\"2026-01-01\"^^" + prefixes.abbreviate(dt);
        }
        if (dt.equals(VocabXsd.DATETIME.getIRIString())) {
            return "\"2026-01-01T00:00:00Z\"^^" + prefixes.abbreviate(dt);
        }
        return "\"" + EXAMPLE_STRING + "\"^^" + prefixes.abbreviate(dt);
    }

    // --- graph reading -------------------------------------------------------

    private String label(final IRI term, final DisplayLocale locale) {
        return text(ontology, term, VocabRdfs.LABEL, locale).orElseGet(() -> localName(term));
    }

    private String comment(final IRI term, final DisplayLocale locale) {
        return text(ontology, term, VocabRdfs.COMMENT, locale).orElse("");
    }

    private static Optional<String> text(final ReadableGraph graph, final BlankNodeOrIRI subject, final IRI property,
            final DisplayLocale locale) {
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

    private Stream<RDFTerm> values(final List<BlankNodeOrIRI> subjects, final IRI property) {
        return subjects.stream().flatMap(subject -> shapes.stream(subject, property, null)).map(Triple::getObject);
    }

    private Optional<Integer> integer(final BlankNodeOrIRI subject, final IRI property) {
        return single(shapes, subject, property).filter(Literal.class::isInstance)
                .map(term -> Integer.valueOf(((Literal) term).getLexicalForm()));
    }

    private Stream<RDFTerm> list(final RDFTerm head) {
        List<RDFTerm> items = new ArrayList<>();
        RDFTerm node = head;
        while (node instanceof BlankNodeOrIRI cell && !VocabRdf.NIL.equals(cell)) {
            single(shapes, cell, VocabRdf.FIRST).ifPresent(items::add);
            node = single(shapes, cell, VocabRdf.REST).orElse(VocabRdf.NIL);
        }
        return items.stream();
    }

    private String turtle(final RDFTerm term) {
        if (term instanceof IRI iri) {
            return prefixes.abbreviate(iri.getIRIString());
        }
        if (term instanceof Literal literal) {
            String quoted = "\"" + literal.getLexicalForm().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            if (literal.getLanguageTag().isPresent()) {
                return quoted + "@" + literal.getLanguageTag().get();
            }
            String datatype = literal.getDatatype().getIRIString();
            return datatype.equals(VocabXsd.STRING.getIRIString()) ? quoted
                    : quoted + "^^" + prefixes.abbreviate(datatype);
        }
        return term.toString();
    }

    private static Optional<RDFTerm> single(final ReadableGraph graph, final BlankNodeOrIRI subject,
            final IRI property) {
        return graph.stream(subject, property, null).map(Triple::getObject).findFirst();
    }

    private static boolean isTrue(final RDFTerm term) {
        return term instanceof Literal literal && Boolean.parseBoolean(literal.getLexicalForm());
    }

    private static String localName(final IRI term) {
        String iri = term.getIRIString();
        int cut = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return iri.substring(cut + 1);
    }

    private static IRI sh(final String localName) {
        return RDF_FACTORY.createIRI(SHACL + localName);
    }
}
