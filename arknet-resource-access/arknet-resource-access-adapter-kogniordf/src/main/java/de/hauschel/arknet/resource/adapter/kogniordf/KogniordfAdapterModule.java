// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

/**
 * Marks the kognio-rdf out-adapter of generic resource access before it holds an adapter class.
 *
 * <p>The module serves the out-ports of {@code arknet-resource-access-core} against the store: the
 * catalog of shapes and ontology, the SHACL write gate and the versioned write funnel. Until the
 * first of those classes exists, this one gives {@code arknet-architecture-tests} a class to see
 * in the module; a rule whose {@code that()} clause matches nothing fails rather than passes.
 * It goes away with the first real adapter class.</p>
 */
public final class KogniordfAdapterModule {

    private KogniordfAdapterModule() {
    }
}
