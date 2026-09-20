// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Locale;
import java.util.Objects;

/**
 * A short, enum-like fact shown as a pill next to a card's headline: a requirement's status
 * and priority, a bounded context's subdomain, a glossary term's actor kind.
 *
 * @param kind  the family this badge belongs to; {@link Kind#cssClass()} names the pill's CSS
 *              class, so an unstyled kind still renders, just in the neutral style
 * @param value the value to show (e.g. {@code Accepted}, {@code Must})
 */
public record Badge(Kind kind, String value) {

    public Badge {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    /**
     * A badge's family (issue #103). {@link Known} lists the kinds {@code
     * HtmlReportRenderer}'s CSS actually styles ({@code .pill.status}/{@code .priority}/
     * {@code .type}/{@code .actor}/{@code .subdomain}/{@code .consequence}/{@code .outcome}) - a
     * misspelled kind is now a compile error instead of a silently neutral pill. {@link Custom}
     * is the deliberate escape hatch for a badge that has no dedicated style (yet): it always
     * renders in the neutral style, the same fallback {@link Known} never needs.
     */
    public sealed interface Kind {

        /** @return the CSS class {@code HtmlReportRenderer} styles this kind with. */
        String cssClass();

        /** The badge families {@code HtmlReportRenderer}'s stylesheet defines a pill for. */
        enum Known implements Kind {
            STATUS, PRIORITY, TYPE, ACTOR, SUBDOMAIN,
            /** An {@code arkarch:Consequence}'s {@code POSITIVE}/{@code NEGATIVE}/{@code NEUTRAL} type (issue #382). */
            CONSEQUENCE,
            /** An {@code arkarch:ConsideredOption}'s {@code CHOSEN}/{@code REJECTED} outcome (issue #382). */
            OUTCOME;

            @Override
            public String cssClass() {
                return name().toLowerCase(Locale.ROOT);
            }
        }

        /** A badge kind with no dedicated CSS class of its own; always renders in the neutral style. */
        record Custom(String label) implements Kind {
            public Custom {
                Objects.requireNonNull(label, "label");
            }

            @Override
            public String cssClass() {
                return "neutral";
            }
        }
    }
}
