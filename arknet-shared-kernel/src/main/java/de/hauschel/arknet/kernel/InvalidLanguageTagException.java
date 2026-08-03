// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

/**
 * Raised when a caller-supplied language argument is not a well-formed BCP-47 tag.
 *
 * <p>Exists so a malformed tag fails loudly instead of being silently mistagged. {@link
 * java.util.Locale#forLanguageTag(String)} is deliberately lenient - per its own javadoc, an
 * ill-formed tag is not rejected but has its unparsable trailing part dropped, which can degrade
 * a typo like {@code "de_DE"} (the Java {@link java.util.Locale} convention uses an underscore,
 * BCP-47 uses a hyphen) all the way down to {@code "und"} (undetermined) without ever throwing.
 * {@link LanguageTag#canonicalize(String)} uses {@link java.util.Locale.Builder#setLanguageTag}
 * instead, which does throw on exactly this class of input, and wraps that failure in this type
 * at the kernel boundary every write path already depends on.</p>
 */
public class InvalidLanguageTagException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String tag;

    /**
     * @param tag   the ill-formed tag as the caller supplied it
     * @param cause the underlying {@link java.util.IllformedLocaleException}
     */
    public InvalidLanguageTagException(String tag, Throwable cause) {
        super("not a well-formed BCP-47 language tag: \"" + tag + "\"", cause);
        this.tag = tag;
    }

    /**
     * @return the ill-formed tag as the caller supplied it
     */
    public String tag() {
        return tag;
    }
}
