// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * The read side of the store for generic access: every read of one call runs in one read
 * transaction, so a resource, its neighbours and its history come from the same state.
 *
 * <p>The port carries every read the generic read tools need - a resource by handle, a listing of a
 * type and the full-text search - so that tools added on top of it leave it unchanged.</p>
 */
public interface ResourceReader {

    /**
     * Runs {@code reading} against one consistent state of a project.
     *
     * @param projectId the project to read
     * @param reading   the reads; they must not write and may run more than once
     * @param <T>       the result type
     * @return what {@code reading} returned
     */
    <T> T read(ProjectId projectId, Function<Snapshot, T> reading);

    /** The reads available inside one {@link #read} call. */
    interface Snapshot {

        /**
         * The current slice of a resource: its triples, those of its fragment children and of the
         * blank nodes reachable from them.
         *
         * @param resource the resource's IRI, without a {@code #}
         * @return the slice, empty if the store holds no such resource
         */
        ReadableGraph slice(IRI resource);

        /**
         * The stored triples, of any resource of the project or of its children, whose object is
         * {@code resource}.
         *
         * @param resource the resource's IRI
         * @return the triples, empty if nothing points at it
         */
        ReadableGraph pointingAt(IRI resource);

        /**
         * The IRI a stored blank node hangs under: the subject reached by following the edges that
         * point at the node, through further blank nodes, up to the first IRI - the resource or the
         * fragment child whose slice holds the node.
         *
         * @param node a blank node of a triple this snapshot returned
         * @return that IRI, empty if no IRI reaches the node
         */
        Optional<IRI> holding(BlankNode node);

        /**
         * The living resources carrying a business code.
         *
         * @param code the code, e.g. {@code FR-1}
         * @return their IRIs; more than one means the code is ambiguous
         */
        List<IRI> withCode(String code);

        /**
         * The deleted resource that kept a business code.
         *
         * @param code the code
         * @return the IRI the code is kept for, empty if no deleted resource kept it
         */
        Optional<IRI> deletedWithCode(String code);

        /**
         * What the store still knows of a deleted resource.
         *
         * @param resource the resource's IRI
         * @return the deletion, empty if the resource is not deleted
         */
        Optional<Deletion> deletion(IRI resource);

        /**
         * The history of a resource, oldest first.
         *
         * @param resource the resource's IRI
         * @return its entries, empty for a resource written past the versioning or never written
         */
        List<HistoryEntry> history(IRI resource);

        /**
         * Whether the resource's draft was ever published and whether it changed since.
         *
         * @param resource the resource's IRI
         * @return {@code NEVER_PUBLISHED}, {@code PUBLISHED_UNCHANGED},
         *         {@code PUBLISHED_WITH_UNPUBLISHED_CHANGES} or {@code DELETED}
         */
        String publishState(IRI resource);

        /**
         * The living resources typed with one of {@code classes}; the caller names every subclass
         * it means.
         *
         * @param classes the classes
         * @return the resource IRIs, distinct, in no particular order
         */
        Set<IRI> typedWith(Set<IRI> classes);

        /**
         * Every stored statement whose literal contains {@code text}, case-insensitively, in any
         * language and datatype.
         *
         * @param text the text to find; must not be blank
         * @return the matching statements
         */
        ReadableGraph literalsContaining(String text);
    }

    /**
     * A deleted resource.
     *
     * @param resource    its IRI
     * @param deletedAt   when it was deleted, empty for a deletion from before versioning
     * @param comment     the comment of the deletion, if any
     * @param code        the business code it kept, if any
     * @param lastVersion the root statements of its last version, empty if not recorded
     */
    record Deletion(IRI resource, Optional<String> deletedAt, Optional<String> comment, Optional<String> code,
            ReadableGraph lastVersion) {

        /** Checks the components. */
        public Deletion {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(deletedAt, "deletedAt");
            Objects.requireNonNull(comment, "comment");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(lastVersion, "lastVersion");
        }
    }

    /**
     * One entry of a resource's history.
     *
     * @param sequence  its running number
     * @param tombstone whether it records a deletion rather than a published version
     * @param created   when it was recorded, ISO-8601
     * @param version   the content-addressed version it names, empty for a tombstone without one
     * @param comment   its comment, if any
     */
    record HistoryEntry(long sequence, boolean tombstone, String created, Optional<String> version,
            Optional<String> comment) {

        /** Checks the components. */
        public HistoryEntry {
            Objects.requireNonNull(created, "created");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(comment, "comment");
        }
    }
}
