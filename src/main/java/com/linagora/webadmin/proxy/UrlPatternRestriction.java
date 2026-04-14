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

public record UrlPatternRestriction(String backingClaim, Operator operator) {

    public enum Operator {
        EQUALS {
            @Override
            public String extractExpectedValue(String claimValue) {
                return claimValue;
            }
        },
        HAS_DOMAIN {
            @Override
            public String extractExpectedValue(String claimValue) {
                int atIndex = claimValue.indexOf('@');
                if (atIndex < 0) {
                    throw new AccessForbiddenException(
                        "Claim value is not a valid email address for HAS_DOMAIN operator");
                }
                return claimValue.substring(atIndex + 1);
            }
        };

        public abstract String extractExpectedValue(String claimValue);
    }
}
