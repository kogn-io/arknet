// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BusinessCodesTest {

    @Test
    void ordersByRunningNumberNotLexicographically() {
        final List<String> sorted = List.of("ADR-2", "ADR-10", "ADR-1").stream()
                .sorted(BusinessCodes.ORDER).toList();

        assertThat(sorted).containsExactly("ADR-1", "ADR-2", "ADR-10");
    }

    @Test
    void groupsByPrefixBeforeComparingRunningNumbers() {
        final List<String> sorted = List.of("NFR-1", "FR-2", "FR-10", "FR-1").stream()
                .sorted(BusinessCodes.ORDER).toList();

        assertThat(sorted).containsExactly("FR-1", "FR-2", "FR-10", "NFR-1");
    }

    /** Use-case codes ({@code UC1}, {@code UC10}) carry no separator before the running number. */
    @Test
    void ordersCodesWithNoSeparatorBeforeTheRunningNumberNumericallyToo() {
        final List<String> sorted = List.of("UC2", "UC10", "UC1").stream()
                .sorted(BusinessCodes.ORDER).toList();

        assertThat(sorted).containsExactly("UC1", "UC2", "UC10");
    }

    @Test
    void fallsBackToLexicographicOrderWhenTheSuffixDoesNotParseAsANumber() {
        final List<String> sorted = List.of("FR-b", "FR-a", "FR-1").stream()
                .sorted(BusinessCodes.ORDER).toList();

        assertThat(sorted).containsExactly("FR-1", "FR-a", "FR-b");
    }
}
