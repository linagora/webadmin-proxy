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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;

@Singleton
public class AllowedUrlsHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AllowedUrlsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OidcTokenCache tokenCache;

    @Inject
    public AllowedUrlsHandler(OidcTokenCache tokenCache) {
        this.tokenCache = tokenCache;
    }

    public Mono<Void> handle(HttpServerResponse response, String token) {
        return tokenCache.resolve(token)
            .flatMap(auth -> checkUserAuthorized(auth)
                .then(serveAllowedUrls(auth, response)))
            .onErrorResume(AccessForbiddenException.class, e -> {
                LOGGER.info("Access forbidden for allowed URLs endpoint", e);
                return response.status(403).send().then();
            })
            .onErrorResume(OidcAuthenticationException.class, e -> {
                LOGGER.info("Authentication rejected for allowed URLs endpoint", e);
                return response.status(401).send().then();
            });
    }

    private Mono<Void> checkUserAuthorized(AuthenticatedRequest auth) {
        List<String> authorizedUsers = auth.clientConfiguration().authorizedUsers();
        if (authorizedUsers.isEmpty()) {
            return Mono.empty();
        }
        if (authorizedUsers.contains(auth.user())) {
            return Mono.empty();
        }
        return Mono.error(new AccessForbiddenException(
            "User '" + auth.user() + "' is not in the authorized users list for client '" + auth.clientId() + "'"));
    }

    private Mono<Void> serveAllowedUrls(AuthenticatedRequest auth, HttpServerResponse response) {
        List<AllowedUrl> allowedUrls = auth.clientConfiguration().allowedUrls();
        if (allowedUrls.isEmpty()) {
            return response.status(204).send().then();
        }
        try {
            ArrayNode array = MAPPER.createArrayNode();
            for (AllowedUrl url : allowedUrls) {
                ObjectNode node = MAPPER.createObjectNode();
                if (!url.verbs().isEmpty()) {
                    ArrayNode verbsNode = node.putArray("verb");
                    url.verbs().forEach(verbsNode::add);
                }
                node.put("endpoint", url.endpointPattern());
                array.add(node);
            }
            byte[] json = MAPPER.writeValueAsBytes(array);
            return response.status(200)
                .header("Content-Type", "application/json")
                .sendByteArray(Mono.just(json))
                .then();
        } catch (Exception e) {
            LOGGER.error("Failed to serialize allowed URLs", e);
            return response.status(500).send().then();
        }
    }
}
