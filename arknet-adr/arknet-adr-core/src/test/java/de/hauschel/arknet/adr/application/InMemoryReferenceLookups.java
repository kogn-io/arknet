// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.adr.application.port.out.TermLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * In-memory test doubles for the three cross-context lookup ports.
 *
 * <p>Hand-rolled fakes (not mocks): a code must be {@link Fake#register registered} before it
 * resolves, mirroring the real adapters' contract that an unknown code is rejected rather than
 * silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support}, a
 * module {@code arknet-adr-core} does not (and must not) depend on; the ports themselves only
 * promise "some runtime exception", so this fake's own signal is enough to prove
 * {@code AdrService#add} lets a lookup failure propagate.</p>
 */
final class InMemoryReferenceLookups {

    private InMemoryReferenceLookups() {
    }

    /** Shared behaviour of both fakes: a registry of known codes and a loud miss. */
    static class Fake {

        private final Map<String, ResourceId> known = new HashMap<>();
        private final String kind;

        Fake(String kind) {
            this.kind = kind;
        }

        void register(String code, ResourceId resourceId) {
            known.put(code, resourceId);
        }

        ResourceId resolve(String code) {
            ResourceId resolved = known.get(code);
            if (resolved == null) {
                throw new NoSuchElementException("fake lookup: unknown " + kind + " code '" + code + "'");
            }
            return resolved;
        }
    }

    /** Fake {@link RequirementLookup}. */
    static final class Requirements extends Fake implements RequirementLookup {

        Requirements() {
            super("requirement");
        }

        @Override
        public ResourceId resolveByCode(ProjectId projectId, String requirementCode) {
            return resolve(requirementCode);
        }
    }

    /** Fake {@link BoundedContextLookup}. */
    static final class BoundedContexts extends Fake implements BoundedContextLookup {

        BoundedContexts() {
            super("bounded context");
        }

        @Override
        public ResourceId resolveByCode(ProjectId projectId, String boundedContextCode) {
            return resolve(boundedContextCode);
        }
    }

    /** Fake {@link TermLookup} (kogn-io/arknet#393). */
    static final class Terms extends Fake implements TermLookup {

        Terms() {
            super("term");
        }

        @Override
        public ResourceId resolveByCode(ProjectId projectId, String termCode) {
            return resolve(termCode);
        }
    }
}
