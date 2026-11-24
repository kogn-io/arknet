// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

/**
 * The IRIs of the resource-access vocabulary ({@code arkres:}) as plain string constants.
 *
 * <p>This is the one namespace generic resource access names in its code. It belongs to no
 * bounded context (ADR-69): its terms mean the same for every resource type. Every class and
 * property of a bounded context - and of the shared utility vocabulary {@code arknet:} - is read
 * at runtime from the ontology and the shapes, never written down here or anywhere else in this
 * component; {@code arknet-architecture-tests} holds that.</p>
 *
 * <p>The constants live in the core rather than next to the {@code Ark*Vocabulary} classes of
 * {@code arknet-persistence-support}: those mirror the namespaces of the bounded contexts, this
 * one is the language of the component itself. The ontology side is
 * {@code arknet-ontology/src/main/resources/arknet-resource.ttl};
 * {@code ResourceVocabularyMatchesOntologyTest} keeps the two in step.</p>
 */
public final class ArkresVocabulary {

    /** The {@code arkres:} namespace. */
    public static final String NAMESPACE = "https://w3id.org/arknet/resource#";

    /** {@code arkres:Activity} - one change to one resource, stated in a submitted document. */
    public static final String ACTIVITY = NAMESPACE + "Activity";

    /** {@code arkres:Create} - creates a new resource of a resource type. */
    public static final String CREATE = NAMESPACE + "Create";

    /** {@code arkres:Update} - replaces values of fields of an existing resource. */
    public static final String UPDATE = NAMESPACE + "Update";

    /** {@code arkres:AddTo} - adds values or child resources to a multi-valued field. */
    public static final String ADD_TO = NAMESPACE + "AddTo";

    /** {@code arkres:RemoveFrom} - removes values or child resources from a multi-valued field. */
    public static final String REMOVE_FROM = NAMESPACE + "RemoveFrom";

    /** {@code arkres:Delete} - deletes an existing resource; its history is kept. */
    public static final String DELETE = NAMESPACE + "Delete";

    /** {@code arkres:object} - the one resource an activity acts on. */
    public static final String OBJECT = NAMESPACE + "object";

    /** {@code arkres:set} - the blank node carrying the new values of an update. */
    public static final String SET = NAMESPACE + "set";

    /** {@code arkres:clear} - a field whose values an update removes. */
    public static final String CLEAR = NAMESPACE + "clear";

    /** {@code arkres:field} - the multi-valued field an add-to or remove-from activity changes. */
    public static final String FIELD = NAMESPACE + "field";

    /** {@code arkres:value} - the value an add-to activity adds or a remove-from activity removes. */
    public static final String VALUE = NAMESPACE + "value";

    /** {@code arkres:position} - the position, before the document, of the child a remove-from removes. */
    public static final String POSITION = NAMESPACE + "position";

    /** {@code arkres:ResourceType} - a class whose instances are resources of their own. */
    public static final String RESOURCE_TYPE = NAMESPACE + "ResourceType";

    /** {@code arkres:codePrefix} - the literal every business code of a resource type starts with. */
    public static final String CODE_PREFIX = NAMESPACE + "codePrefix";

    /** {@code arkres:childKind} - marks a field owning its children; the fragment kind of their names. */
    public static final String CHILD_KIND = NAMESPACE + "childKind";

    /** {@code arkres:orderedBy} - the property carrying the position of an owned child. */
    public static final String ORDERED_BY = NAMESPACE + "orderedBy";

    /** {@code arkres:initialStatus} - marks a status field; the value every created resource starts with. */
    public static final String INITIAL_STATUS = NAMESPACE + "initialStatus";

    /** {@code arkres:Transition} - an allowed change of a status field from one value to another. */
    public static final String TRANSITION_CLASS = NAMESPACE + "Transition";

    /** {@code arkres:transition} - one transition a status field allows. */
    public static final String TRANSITION = NAMESPACE + "transition";

    /** {@code arkres:fromStatus} - the status a transition leaves. */
    public static final String FROM_STATUS = NAMESPACE + "fromStatus";

    /** {@code arkres:toStatus} - the status a transition reaches. */
    public static final String TO_STATUS = NAMESPACE + "toStatus";

    /** {@code arkres:condition} - a shape the resource must satisfy after the document to take a transition. */
    public static final String CONDITION = NAMESPACE + "condition";

    /** {@code arkres:deletableIn} - a status in which a resource of a status field may be deleted. */
    public static final String DELETABLE_IN = NAMESPACE + "deletableIn";

    /** {@code arkres:FieldGroup} - fields a document changes freely only in the statuses the group names. */
    public static final String FIELD_GROUP_CLASS = NAMESPACE + "FieldGroup";

    /** {@code arkres:fieldGroup} - one field group of a status field. */
    public static final String FIELD_GROUP = NAMESPACE + "fieldGroup";

    /** {@code arkres:groupField} - a field of a field group. */
    public static final String GROUP_FIELD = NAMESPACE + "groupField";

    /** {@code arkres:editableIn} - a status in which a document changes a field group freely. */
    public static final String EDITABLE_IN = NAMESPACE + "editableIn";

    /** {@code arkres:immutable} - marks a field whose values stay as the create wrote them. */
    public static final String IMMUTABLE = NAMESPACE + "immutable";

    /** {@code arkres:Default} - a value the engine writes into a field when a transition is taken. */
    public static final String DEFAULT_CLASS = NAMESPACE + "Default";

    /** {@code arkres:default} - one default value a transition writes. */
    public static final String DEFAULT = NAMESPACE + "default";

    /** {@code arkres:defaultField} - the field a default value is written into. */
    public static final String DEFAULT_FIELD = NAMESPACE + "defaultField";

    /** {@code arkres:defaultValue} - the value of a default: a term, or {@code arkres:today}. */
    public static final String DEFAULT_VALUE = NAMESPACE + "defaultValue";

    /** {@code arkres:today} - stands for the day the document is applied, written as an {@code xsd:date}. */
    public static final String TODAY = NAMESPACE + "today";

    private ArkresVocabulary() {
    }
}
