// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the domain-agnostic CURIE / IRI resolution.
 */
class PrefixesTest {

    private final Prefixes prefixes = Prefixes.defaults();

    @Test
    void expandsCurieToInstanceIri() {
        assertThat(prefixes.toIri("req:FR-1"))
                .contains("https://w3id.org/arknet/model/requirement/FR-1");
    }

    @Test
    void expandsVocabularyCurie() {
        assertThat(prefixes.toIri("skos:Concept"))
                .contains("http://www.w3.org/2004/02/skos/core#Concept");
    }

    @Test
    void fullIriResolvesToItself() {
        String iri = "https://w3id.org/arknet/model/requirement/FR-1";
        assertThat(prefixes.toIri(iri)).contains(iri);
    }

    @Test
    void anglebracketedIriIsUnwrapped() {
        assertThat(prefixes.toIri("<https://w3id.org/arknet/model/term/TERM-1>"))
                .contains("https://w3id.org/arknet/model/term/TERM-1");
    }

    @Test
    void bareBusinessIdIsNotExpandable() {
        assertThat(prefixes.toIri("FR-1")).isEmpty();
    }

    @Test
    void unknownPrefixIsNotExpandable() {
        assertThat(prefixes.toIri("nope:X")).isEmpty();
    }

    @Test
    void shortensInstanceIriToCurie() {
        assertThat(prefixes.toCurie("https://w3id.org/arknet/model/requirement/FR-1"))
                .isEqualTo("req:FR-1");
    }

    @Test
    void shortensVocabularyIriToCurie() {
        assertThat(prefixes.toCurie("https://w3id.org/arknet/requirements#status"))
                .isEqualTo("arkreq:status");
    }

    @Test
    void picksMostSpecificNamespaceWhenShortening() {
        // core base https://w3id.org/arknet/model/ would also match; the more specific
        // requirement/ namespace must win.
        assertThat(prefixes.toCurie("https://w3id.org/arknet/model/requirement/FR-9"))
                .isEqualTo("req:FR-9");
    }

    @Test
    void unknownIriIsReturnedUnchanged() {
        String iri = "https://example.org/thing/1";
        assertThat(prefixes.toCurie(iri)).isEqualTo(iri);
    }

    @Test
    void curieAndFullIriExpandToSameIri() {
        Optional<String> viaCurie = prefixes.toIri("req:FR-1");
        Optional<String> viaIri = prefixes.toIri("https://w3id.org/arknet/model/requirement/FR-1");
        assertThat(viaCurie).isEqualTo(viaIri);
    }
}
