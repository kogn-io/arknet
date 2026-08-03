// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * Canonicalizes and validates a caller-supplied BCP-47 language tag - the one place every write
 * path that accepts a {@code language} argument (term/project) routes it through, so a
 * not-well-formed tag is rejected once, consistently, rather than silently mistagged
 * differently by each out-adapter.
 *
 * <p>Uses {@link Locale.Builder#setLanguageTag(String)} rather than the more commonly reached
 * for {@link Locale#forLanguageTag(String)}: the latter is deliberately lenient (its own javadoc
 * says so) and never throws, dropping an unparsable trailing part instead - a caller typo like
 * {@code "de_DE"} (Java {@link Locale}'s own {@code toString()} convention uses an underscore,
 * BCP-47 uses a hyphen) degrades all the way to {@code "und"} (undetermined) with no signal that
 * anything went wrong. {@link Locale.Builder} parses the same grammar strictly and throws
 * {@link IllformedLocaleException} on exactly this input, which this class turns into the
 * kernel's own {@link InvalidLanguageTagException} instead of leaking a {@code java.util} type
 * across the port boundary.</p>
 */
public final class LanguageTag {

    private LanguageTag() {
    }

    /**
     * Canonicalizes {@code tag} to its normalized BCP-47 form (e.g. {@code "DE"} -&gt;
     * {@code "de"}), or returns {@code null} unchanged for a {@code null} tag (the "untagged
     * literal" case every caller of this method already treats as legal).
     *
     * @param tag a BCP-47 language tag, or {@code null}
     * @return the canonicalized tag, or {@code null} if {@code tag} was {@code null}
     * @throws InvalidLanguageTagException if {@code tag} is not a well-formed BCP-47 tag
     */
    public static String canonicalize(String tag) {
        if (tag == null) {
            return null;
        }
        try {
            return new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
        } catch (IllformedLocaleException e) {
            throw new InvalidLanguageTagException(tag, e);
        }
    }
}
