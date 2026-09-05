// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ProjectId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A registered project: the store object that replaces the old, derived "workspace" notion.
 * Exactly one dataset holds the data of exactly one project.
 *
 * <p>Value object of the project component. All invariants are enforced in the compact
 * constructor; instances are immutable and their collection is defensively copied.</p>
 *
 * <p><strong>A project without an anchor is unreachable, so it is not a legal state.</strong>
 * There is no default anchor and no fallback to a server-side working directory - a project that
 * could exist without one would be exactly the silent-default failure mode this model exists to
 * close. {@code null} or an empty anchor list is therefore rejected here, in the constructor,
 * rather than tolerated and caught later at the point a lookup fails to find anything to
 * return.</p>
 *
 * @param id              opaque, unchanging identity of this project (never derived from an
 *                        anchor); minted once, directly by the application service, and stable
 *                        across relabelling and across attaching further anchors
 * @param label           the project's human-readable, cross-project-unique name; maps to
 *                        {@code dcterms:identifier} when the project describes itself in its own
 *                        dataset. Distinct from {@code id}: the id is opaque
 *                        and never interpreted, the label is what a human reads in a report or a
 *                        rename dialog
 * @param anchors         the client-supplied handles this project is reachable by, {@code 1..n};
 *                        never empty (see above). Several anchors on the same project cover git
 *                        worktrees, multiple IDE directories of the same checkout, or a copy at
 *                        another location
 * @param description     the project's optional, free-text description (issue #110), or
 *                        {@code null} if none is set. A read projection exactly like
 *                        {@code Term#prefLabel}: a project may carry several language-tagged
 *                        {@code dcterms:description} literals, and this field holds the one a
 *                        read path already selected via {@code DisplayLocale} - it is never a
 *                        multi-value holder itself. Maps to {@code dcterms:description};
 *                        written and corrected only through the project component's targeted
 *                        description patch, never through the replace-by-identity registry write
 *                        {@code label}/{@code anchors} share, so an unrelated rename or attached
 *                        anchor never touches it
 * @param defaultLanguage the project's optional, single default display/write language, as a
 *                        BCP-47 language tag (e.g. {@code "de"}), or {@code null} if none is
 *                        configured. Maps to {@code arkprj:defaultLanguage}. A <em>fallback</em>:
 *                        which language a call that names none is written and read under
 * @param maintainedLanguages the BCP-47 tags of the languages this project undertakes to maintain
 *                        its model in (kogn-io/arknet#412), never {@code null} but possibly empty.
 *                        Maps to the multi-valued {@code arkprj:maintainedLanguage}. A
 *                        <em>commitment</em>, not a fallback: it is the target state that makes a
 *                        resource carrying only some of those languages describable as incomplete
 *                        at all. Empty means no commitment, the state every project was in before
 *                        this field existed
 */
public record Project(ProjectId id, String label, List<Anchor> anchors, String description,
        String defaultLanguage, List<String> maintainedLanguages) {

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException("a project must hold at least one anchor - "
                    + "an anchorless project would be unreachable and there is no default");
        }
        anchors = List.copyOf(anchors);
        // Deliberately deduplicated but NOT canonicalized and NOT checked against
        // defaultLanguage here: this constructor also runs on the read path, and a store-first
        // registry entry carrying a malformed tag, or a default language outside its own set, has
        // to stay readable - a project that cannot be constructed is a project no tool can
        // correct. Canonicalization and the pair invariant live on the write paths instead
        // (canonicalLanguages / requireDefaultLanguageMaintained below).
        maintainedLanguages = maintainedLanguages == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(maintainedLanguages));
    }

    /**
     * Creates a project without a description or a default language - the common case, and the
     * shape every call site had before issue #228/#110 introduced those two optional fields.
     * Kept as a secondary constructor (not the canonical one) so every existing call site that
     * never meant to touch either field did not have to start passing two {@code null}s.
     */
    public Project(ProjectId id, String label, List<Anchor> anchors) {
        this(id, label, anchors, null, null, List.of());
    }

    /**
     * Creates a project that maintains no declared language set - the shape every call site had
     * before kogn-io/arknet#412 introduced {@link #maintainedLanguages()}. Kept as a secondary
     * constructor for the same reason the three-argument one above is.
     */
    public Project(ProjectId id, String label, List<Anchor> anchors, String description,
            String defaultLanguage) {
        this(id, label, anchors, description, defaultLanguage, List.of());
    }

    /**
     * Normalizes a caller-supplied set of maintained languages: every tag canonicalized to its
     * BCP-47 form ({@code "DE"} -&gt; {@code "de"}), duplicates removed, order preserved.
     *
     * <p>Canonicalizing here rather than only in the out-adapter is what makes {@link
     * #requireDefaultLanguageMaintained} and the stored literals agree: without it, a set given as
     * {@code ["DE"]} would be stored as {@code ["de"]} and then fail to contain a default language
     * given as {@code "de"} - a rejection produced by casing alone.</p>
     *
     * @param tags the caller's tags, or {@code null} for none
     * @return the canonicalized, duplicate-free tags in the order given, never {@code null}
     * @throws IllegalArgumentException if a tag is blank
     * @throws de.hauschel.arknet.kernel.InvalidLanguageTagException if a tag is not well-formed
     */
    public static List<String> canonicalLanguages(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> canonical = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("a maintained language must be a BCP-47 tag, "
                        + "not a blank value - pass an empty list to maintain no declared language set");
            }
            canonical.add(LanguageTag.canonicalize(tag.strip()));
        }
        return List.copyOf(new LinkedHashSet<>(canonical));
    }

    /**
     * The pair invariant between the two language fields: where a project declares a non-empty
     * maintained set, its default language has to be one of its members.
     *
     * <p>Both arguments are canonicalized before they are compared, so a caller need not have
     * normalized either - the check must never fail on casing alone. Neither an empty {@code
     * maintainedLanguages} (no commitment) nor a {@code null} {@code defaultLanguage} (no
     * fallback at all) can violate it; only a fallback that genuinely points outside a declared
     * set does.</p>
     *
     * @param defaultLanguage     the default language the project would have after the write, or
     *                            {@code null} if it has none
     * @param maintainedLanguages the maintained set the project would have after the write
     * @throws DefaultLanguageNotMaintainedException if the default language points outside a
     *                                               non-empty maintained set
     */
    public static void requireDefaultLanguageMaintained(String defaultLanguage,
            List<String> maintainedLanguages) {
        if (defaultLanguage == null || maintainedLanguages == null || maintainedLanguages.isEmpty()) {
            return;
        }
        String canonicalDefault = LanguageTag.canonicalize(defaultLanguage.strip());
        List<String> canonicalSet = canonicalLanguages(maintainedLanguages);
        if (!canonicalSet.contains(canonicalDefault)) {
            throw new DefaultLanguageNotMaintainedException(canonicalDefault, canonicalSet);
        }
    }
}
