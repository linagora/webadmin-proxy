/********************************************************************
 *  Webadmin Proxy                                                   *
 *                                                                   *
 *  Copyright (C) 2025 Linagora                                      *
 *                                                                   *
 *  This program is free software: you can redistribute it and/or   *
 *  modify it under the terms of the GNU Affero General Public       *
 *  License as published by the Free Software Foundation, either     *
 *  version 3 of the License, or (at your option) any later version. *
 *                                                                   *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 ********************************************************************/

package com.linagora.webadmin.proxy;

import java.util.Optional;

/**
 * Extracts the domain part of an email address.
 *
 * <p>Only values holding exactly one '@', followed by a non-blank domain part, are email addresses.
 * Shared by {@link UrlPatternRestriction.Operator#HAS_DOMAIN} and the {@code /.proxy/myDomain} endpoint,
 * so that the domain reported to the caller is the one its restrictions grant.</p>
 */
public final class EmailDomain {

    private static final char AT = '@';

    private EmailDomain() {
    }

    public static Optional<String> of(String emailAddress) {
        int atIndex = emailAddress.indexOf(AT);
        return Optional.of(atIndex)
            .filter(index -> index >= 0 && index == emailAddress.lastIndexOf(AT))
            .map(index -> emailAddress.substring(index + 1))
            .filter(domain -> !domain.isBlank());
    }
}
