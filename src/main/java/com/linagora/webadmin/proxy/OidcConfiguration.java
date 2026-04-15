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

import java.net.URL;
import java.time.Duration;
import java.util.List;

import org.apache.james.jwt.introspection.IntrospectionEndpoint;

public record OidcConfiguration(URL userInfoUrl,
                                 IntrospectionEndpoint introspectionEndpoint,
                                 List<String> audiences,
                                 String userClaim,
                                 Duration tokenCacheExpiration) {
}
