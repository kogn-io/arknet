// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * The core's own field-holding {@link Term} implementation - what {@link Term#of} returns.
 *
 * <p>This is what the whole type used to be, as a {@code record}: identity, code and the three
 * mutable fields, with the invariants checked on the way in. Two properties were lost in the
 * move to an interface (spike, issue #168) and are worth naming rather than glossing over: it is
 * no longer immutable (the interface has setters, so this cannot be a record), and its equality
 * is no longer structural. Nothing in the component compared whole terms, so the second loss is
 * currently free - but it is a loss, not a non-issue.</p>
 */
final class PlainTerm implements Term {

    private final TermId id;
    private final TermCode code;
    private String prefLabel;
    private String definition;
    private ActorFacet actorFacet;

    PlainTerm(final TermId id, final TermCode code, final String prefLabel, final String definition,
            final ActorFacet actorFacet) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.prefLabel = Term.requireLabel(prefLabel);
        this.definition = Term.requireDefinition(definition);
        this.actorFacet = actorFacet;
    }

    @Override
    public TermId id() {
        return id;
    }

    @Override
    public TermCode code() {
        return code;
    }

    @Override
    public String prefLabel() {
        return prefLabel;
    }

    @Override
    public void prefLabel(final String value) {
        this.prefLabel = Term.requireLabel(value);
    }

    @Override
    public String definition() {
        return definition;
    }

    @Override
    public void definition(final String value) {
        this.definition = Term.requireDefinition(value);
    }

    @Override
    public ActorFacet actorFacet() {
        return actorFacet;
    }

    @Override
    public void actorFacet(final ActorFacet value) {
        this.actorFacet = value;
    }

    @Override
    public boolean equals(final Object other) {
        return Term.equal(this, other);
    }

    @Override
    public int hashCode() {
        return Term.hash(this);
    }

    @Override
    public String toString() {
        return "Term[" + code.value() + " " + prefLabel + "]";
    }
}
