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

    private ArkresVocabulary() {
    }
}
