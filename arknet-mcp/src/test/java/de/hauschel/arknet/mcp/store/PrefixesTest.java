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

    /**
     * A prefix that is not a syntactically valid RFC 3986 URI scheme either - it starts with a
     * digit, which no scheme may - stays rejected rather than being guessed at. A scheme-shaped
     * unknown prefix like {@code urn} is a different case; see
     * {@link #schemeShapedUnknownPrefixExpandsToItselfAsANonHierarchicalIri()}.
     */
    @Test
    void unknownPrefixIsNotExpandableWhenItIsNotAValidUriScheme() {
        assertThat(prefixes.toIri("1nope:X")).isEmpty();
    }

    /**
     * Regression test for issue #305: {@code urn:}/{@code mailto:}/... IRIs never contain
     * {@code "://"}, yet are complete, self-authoritative IRIs in their own right - their
     * "prefix" is an RFC 3986 URI scheme, not a CURIE prefix that failed to resolve against a
     * known {@link Prefixes} binding. Before this fix such a handle fell through to "unknown
     * prefix" even though it was never meant to be one.
     */
    @Test
    void schemeShapedUnknownPrefixExpandsToItselfAsANonHierarchicalIri() {
        String urn = "urn:uuid:6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        assertThat(prefixes.toIri(urn)).contains(urn);
    }

    @Test
    void mailtoHandleExpandsToItself() {
        String mailto = "mailto:someone@example.org";
        assertThat(prefixes.toIri(mailto)).contains(mailto);
    }

    /**
     * The {@code <...>} explicit-IRI form (issue #305's second half) must resolve a
     * non-hierarchical IRI just as reliably as the bare form above.
     */
    @Test
    void angleBracketedNonHierarchicalIriExpandsToItself() {
        String urn = "urn:uuid:6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        assertThat(prefixes.toIri("<" + urn + ">")).contains(urn);
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
    void shortensBoundedContextVocabularyIriToCurie() {
        assertThat(prefixes.toCurie("https://w3id.org/arknet/ddd#domainVision"))
                .isEqualTo("arkddd:domainVision");
    }

    @Test
    void expandsBoundedContextVocabularyCurie() {
        assertThat(prefixes.toIri("arkddd:BoundedContext"))
                .contains("https://w3id.org/arknet/ddd#BoundedContext");
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
