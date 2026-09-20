// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link StoreCheckKind}, in particular the ORPHAN value (kogn-io/arknet#473). */
class StoreCheckKindTest {

    @Test
    void carriesExactlyTheFourChecksStoreCheckSupportsToday() {
        assertThat(StoreCheckKind.values()).containsExactly(
                StoreCheckKind.LANGUAGE, StoreCheckKind.ROLE_TERM_DUPLICATE, StoreCheckKind.STEP_ACCEPTANCE,
                StoreCheckKind.ORPHAN);
    }

    @Test
    void parsesOrphanCaseInsensitively() {
        assertThat(StoreCheckKind.parse("orphan")).isEqualTo(StoreCheckKind.ORPHAN);
        assertThat(StoreCheckKind.parse("ORPHAN")).isEqualTo(StoreCheckKind.ORPHAN);
    }

    @Test
    void namesOrphanAmongTheAllowedValuesOfAnUnknownSelectorError() {
        assertThatThrownBy(() -> StoreCheckKind.parse("orphans"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORPHAN");
    }

    @Test
    void namesIncludesOrphan() {
        assertThat(StoreCheckKind.names()).contains("ORPHAN");
    }
}
