// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.out;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.persistence.ChildFragments;

/**
 * The writes of one {@link ResourceRepository#writeAll} call, bound to its transaction. Each subject
 * may be written once per batch. Every read joins the transaction.
 */
public interface ResourceBatch {

    /**
     * The current slice of a resource: its triples, those of its fragment children and the blank
     * nodes reachable from them.
     *
     * @param subject the resource's IRI, without a {@code #}
     * @return the slice, empty if the store holds no such resource
     */
    ReadableGraph slice(IRI subject);

    /**
     * The highest running number among the business codes starting with {@code codePrefix}, those of
     * living resources and those kept by deleted ones.
     *
     * @param codePrefix the literal the codes start with
     * @return the highest running number, {@code 0} if no code starts with the prefix
     */
    int highestRunningNumber(String codePrefix);

    /**
     * The naming state of a resource's children for this write (ADR-64).
     *
     * @param parent  the resource's IRI
     * @param current its current slice, empty for a create
     * @return the naming state
     */
    ChildFragments childFragments(IRI parent, ReadableGraph current);

    /**
     * Creates a resource.
     *
     * @param subject the new resource's IRI
     * @param code    its business code, or {@code null}
     * @param slice   its complete slice
     * @param context validation-only triples the shapes need beside the slice, never persisted
     */
    void create(IRI subject, String code, ReadableGraph slice, ReadableGraph context);

    /**
     * Replaces the slice of an existing resource.
     *
     * @param subject the resource's IRI
     * @param slice   its complete new slice
     * @param context validation-only triples the shapes need beside the slice, never persisted
     */
    void update(IRI subject, ReadableGraph slice, ReadableGraph context);

    /**
     * Deletes a resource; its history stays and its code is never assigned again.
     *
     * @param subject the resource's IRI
     * @param code    its business code, or {@code null}
     */
    void delete(IRI subject, String code);
}
