// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.out;

import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Driven port: creates {@link Term} instances the paired {@link TermRepository} can persist
 * without translating them first (spike, issue #168).
 *
 * <p>This port exists for one reason, and it is worth being honest about which: since
 * {@link Term} is an interface whose out-adapter implementation <em>is</em> an RDF graph, the
 * application service can no longer instantiate a term the adapter is able to write directly. It
 * therefore asks for one. The port carries no capability the domain would recognise ("mint a
 * term object" is not a thing the glossary needs from the outside world the way "store and
 * retrieve terms" is) - it is a construction seam forced by the representation choice, which is
 * exactly the kind of port ADR-007 rejected when it declined to put the SHACL gate in the core.
 * That tension is part of what issue #168 set out to measure.</p>
 *
 * <p>{@link #plain()} is the core's own implementation: it hands back the field-holding
 * {@link Term#of} instance, which is all a test or an in-memory repository ever needs. The
 * composition root wires the persistence-flavoured one instead.</p>
 */
@FunctionalInterface
public interface TermFactory {

    /**
     * Creates a term the paired repository can persist as-is.
     *
     * @param id         the opaque identity minted by the caller, must not be {@code null}
     * @param code       the business code assigned by the caller, must not be {@code null}
     * @param prefLabel  the preferred label, must not be {@code null} or blank
     * @param definition the definition, must not be {@code null} or blank
     * @param actorFacet the optional Actor facette, may be {@code null}
     * @return the new term
     */
    Term newTerm(TermId id, TermCode code, String prefLabel, String definition, ActorFacet actorFacet);

    /**
     * The core's own factory, producing plain field-holding terms.
     *
     * @return a factory delegating to {@link Term#of}
     */
    static TermFactory plain() {
        return Term::of;
    }
}
