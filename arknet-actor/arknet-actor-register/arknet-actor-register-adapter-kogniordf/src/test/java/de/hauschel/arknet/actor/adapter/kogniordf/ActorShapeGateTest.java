// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;

/**
 * The actor gate demands a concrete actor subtype: the abstract {@code arkproc:Actor} alone is
 * rejected, every one of the four subtypes passes.
 */
class ActorShapeGateTest {

    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";

    private final RDF rdf = new SimpleRdf();
    private final ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

    private Graph actorOfType(final String type) {
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(type));
        candidate.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Somebody", "en"));
        return candidate;
    }

    @Test
    void gateRejectsAnActorTypedOnlyAsTheAbstractSuperclass() {
        Graph candidate = actorOfType(ArkprocVocabulary.ACTOR_TYPE);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {ArkprocVocabulary.HUMAN_ACTOR_TYPE, ArkprocVocabulary.SYSTEM_ACTOR_TYPE,
        ArkprocVocabulary.LEGAL_ACTOR_TYPE, ArkprocVocabulary.GROUP_ACTOR_TYPE})
    void gateAcceptsEveryConcreteActorSubtype(final String type) {
        gate.enforce(actorOfType(type));
    }
}
