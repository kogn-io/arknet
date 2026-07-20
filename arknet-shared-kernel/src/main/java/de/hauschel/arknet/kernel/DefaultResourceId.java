// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Objects;

/**
 * Default (and only) implementation of {@link ResourceId}.
 *
 * <p>Package-private so only this kernel package can construct a {@link ResourceId} - callers
 * outside the kernel go through {@link ResourceId#of(String)} or a {@link ResourceIdFactory}.</p>
 *
 * @param value the wrapped IRI string, validated identically to {@link ResourceId#of(String)}
 */
record DefaultResourceId(String value) implements ResourceId {

    DefaultResourceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || !ResourceId.VALID_IRI.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ResourceId must be a non-blank https:// IRI without whitespace, got: " + value);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || ResourceId.FORBIDDEN_IRI_CHARACTERS.indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                        "ResourceId must be a valid IRIREF (RFC 3987 IRI) - forbidden character in: " + value);
            }
        }
    }
}
