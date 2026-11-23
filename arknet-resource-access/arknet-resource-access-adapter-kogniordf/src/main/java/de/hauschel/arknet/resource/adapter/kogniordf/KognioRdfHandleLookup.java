// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.HandleResolver;
import de.hauschel.arknet.resource.application.port.out.HandleLookup;

/**
 * Resolves a handle with the resolver {@code resource_get} and {@code impact_analysis} use, so a
 * document names a resource the same way every other tool does: a bare code is looked up by its
 * {@code dcterms:identifier}, a code held by several resources is rejected, a deleted code resolves
 * to the IRI its tombstone sits on (the write then finds no type for it and rejects the reference).
 */
final class KognioRdfHandleLookup implements HandleLookup {

    private final HandleResolver resolver;

    KognioRdfHandleLookup(final HandleResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String resolve(final ProjectId projectId, final String handle) {
        return resolver.resolve(projectId, handle);
    }
}
