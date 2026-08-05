// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * Canonicalizes and validates a caller-supplied BCP-47 language tag - the one place every write
 * path that accepts a {@code language} argument (requirement/term/use-case/project) routes it
 * through, so a not-well-formed tag is rejected once, consistently, rather than silently
 * mistagged differently by each out-adapter.
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

    /**
     * Resolves the tag a write call actually writes a language-tagged field under: {@code
     * explicit}, canonicalized, if the caller named one; otherwise {@code projectDefaultLanguage}
     * (the resolved project's {@link ResolvedProject#defaultLanguage()}), canonicalized, if the
     * project has one configured; otherwise rejects the call (issue #258).
     *
     * <p>Before this method existed, an omitted {@code language} argument always wrote a plain,
     * untagged literal, regardless of whether the project had a configured default - a design this
     * project deliberately reversed: an untagged write is no longer a caller's default outcome, it
     * is now unreachable unless the project genuinely has no default configured
     * <em>and</em> the caller did not name one either, in which case the call is rejected rather
     * than silently degrading to untagged. This closes the gap that let a single field end up
     * carrying both a language-tagged and an untagged variant at once (e.g. a requirement titled
     * both {@code "..."@de} and {@code "..."} with no tag) after an update that omitted {@code
     * language} was written against a store that already had a tagged literal for that field - the
     * two literals never collided because {@code sh:uniqueLang} only constrains same-tag
     * duplicates, and neither the write path nor any tool could remove or retag the untagged
     * one afterwards.</p>
     *
     * @param explicit               the caller-supplied {@code language} argument, or {@code null}
     *                               if the caller did not name one
     * @param projectDefaultLanguage the resolved project's configured default language, or {@code
     *                               null} if it has none
     * @return the canonicalized tag this write is to use
     * @throws InvalidLanguageTagException     if the winning tag ({@code explicit} or {@code
     *                                          projectDefaultLanguage}) is not a well-formed
     *                                          BCP-47 tag
     * @throws MissingDefaultLanguageException if {@code explicit} is {@code null} and {@code
     *                                          projectDefaultLanguage} is {@code null} too
     */
    public static String resolveWriteLanguage(String explicit, String projectDefaultLanguage) {
        if (explicit != null) {
            return canonicalize(explicit);
        }
        if (projectDefaultLanguage == null) {
            throw new MissingDefaultLanguageException();
        }
        return canonicalize(projectDefaultLanguage);
    }
}
