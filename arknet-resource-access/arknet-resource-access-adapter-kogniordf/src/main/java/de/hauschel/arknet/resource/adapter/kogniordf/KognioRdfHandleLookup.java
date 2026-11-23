// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.Objects;
import java.util.regex.Pattern;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.HandleResolver;
import de.hauschel.arknet.resource.application.port.out.HandleLookup;

/**
 * Resolves a handle with the resolver {@code resource_get} and {@code impact_analysis} use, so a
 * document names a resource the same way every other tool does: a bare code is looked up by its
 * {@code dcterms:identifier}, a code held by several resources is rejected, a deleted code resolves
 * to the IRI its tombstone sits on (the write then finds no type for it and rejects the reference).
 *
 * <p>Only a bare code passes on: the resolver would also return a prefixed name, a full IRI or a
 * blank-node label unchecked, and a relative IRI such as {@code <./req:FR-1>} or {@code <./_:b1>}
 * would reach those branches. A handle with a colon, a slash or a hash is rejected instead.</p>
 */
final class KognioRdfHandleLookup implements HandleLookup {

    private static final Pattern NOT_A_CODE = Pattern.compile("[:/#]");

    private final HandleResolver resolver;

    KognioRdfHandleLookup(final HandleResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String resolve(final ProjectId projectId, final String handle) {
        if (handle.isBlank() || NOT_A_CODE.matcher(handle).find()) {
            throw new IllegalArgumentException("a handle is a bare business code such as FR-12, not '" + handle
                    + "'; name any other resource by its full IRI");
        }
        return resolver.resolve(projectId, handle);
    }
}
