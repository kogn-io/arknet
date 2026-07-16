package de.hauschel.arknet.persistence;

/**
 * SPARQL term-serialization helpers shared by every kognio-rdf out-adapter that builds SPARQL
 * query/update strings by hand (string concatenation, not a query builder).
 *
 * <p>Two syntactic productions are covered: {@code STRING_LITERAL2} ({@code "..."}) and
 * {@code IRIREF} ({@code <...>}). Both grammars are narrow escaping/whitelisting concerns, not
 * domain logic, hence their home here rather than in each adapter.</p>
 */
public final class SparqlTerms {

    private SparqlTerms() {
    }

    /**
     * Escapes the content of a SPARQL {@code STRING_LITERAL2} ({@code "..."}).
     *
     * <p>Order matters: the backslash must be escaped first, otherwise the backslashes
     * introduced by the later replacements would themselves be escaped again.</p>
     *
     * @param lexical the raw lexical form to embed inside double quotes
     * @return the escaped content, without the surrounding quotes - callers wrap it in
     *         {@code "..."} themselves
     */
    public static String escape(String lexical) {
        return lexical.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Checks whether {@code iri} may appear unescaped inside a SPARQL {@code IRIREF}
     * ({@code <...>}).
     *
     * <p>Follows the SPARQL grammar exactly:
     * {@code IRIREF ::= '<' ([^<>"{}|^`\]-[#x00-#x20])* '>'} - none of
     * {@code < > " { } | ^ ` \} and no codepoint {@code <= 0x20} (control characters and
     * space) may occur.</p>
     *
     * @param iri the candidate IRI string (without the angle brackets)
     * @return {@code true} if {@code iri} contains none of the forbidden characters
     */
    public static boolean isValidIriReference(String iri) {
        for (int i = 0; i < iri.length(); i++) {
            char c = iri.charAt(i);
            if (c <= 0x20 || c == '<' || c == '>' || c == '"' || c == '{' || c == '}'
                    || c == '|' || c == '^' || c == '`' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    /**
     * Wraps {@code iri} as a SPARQL {@code IRIREF} ({@code <iri>}).
     *
     * @param iri the IRI string to wrap
     * @return {@code "<" + iri + ">"}
     * @throws IllegalArgumentException if {@code iri} is not a valid {@code IRIREF} body (see
     *                                  {@link #isValidIriReference(String)})
     */
    public static String iriRef(String iri) {
        if (!isValidIriReference(iri)) {
            throw new IllegalArgumentException(
                    "not a valid SPARQL IRIREF (contains a forbidden character): " + iri);
        }
        return "<" + iri + ">";
    }
}
