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

import java.util.List;
import java.util.Optional;

import org.apache.james.jwt.userinfo.UserinfoResponse;

import reactor.core.publisher.Mono;

public record AuthenticatedRequest(String user, String clientId, ClientConfiguration clientConfiguration,
                                    UserinfoResponse userInfo, Optional<String> sessionId) {

    public Mono<Void> checkUserAuthorized() {
        List<String> authorizedUsers = clientConfiguration.authorizedUsers();
        if (authorizedUsers.isEmpty()) {
            return Mono.empty();
        }
        if (authorizedUsers.contains(user)) {
            return Mono.empty();
        }
        return Mono.error(new AccessForbiddenException(
            "User '" + user + "' is not in the authorized users list for client '" + clientId + "'"));
    }
}
