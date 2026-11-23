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

    private ArkresVocabulary() {
    }
}
