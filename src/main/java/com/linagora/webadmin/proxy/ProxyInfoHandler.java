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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;

@Singleton
public class ProxyInfoHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyInfoHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OidcTokenCache tokenCache;

    @Inject
    public ProxyInfoHandler(OidcTokenCache tokenCache) {
        this.tokenCache = tokenCache;
    }

    public Mono<Void> handleWhoami(HttpServerResponse response, String token) {
        return tokenCache.resolve(token)
            .flatMap(auth -> auth.checkUserAuthorized()
                .then(serveJson(response, node -> node.put("email", auth.user()))))
            .onErrorResume(AccessForbiddenException.class, e -> {
                LOGGER.info("Access forbidden for whoami endpoint", e);
                return response.status(403).send().then();
            })
            .onErrorResume(OidcAuthenticationException.class, e -> {
                LOGGER.info("Authentication rejected for whoami endpoint", e);
                return response.status(401).send().then();
            });
    }

    public Mono<Void> handleMyDomain(HttpServerResponse response, String token) {
        return tokenCache.resolve(token)
            .flatMap(auth -> auth.checkUserAuthorized()
                .then(serveJson(response, node -> node.put("domain", resolveDomain(auth)))))
            .onErrorResume(AccessForbiddenException.class, e -> {
                LOGGER.info("Access forbidden for myDomain endpoint", e);
                return response.status(403).send().then();
            })
            .onErrorResume(OidcAuthenticationException.class, e -> {
                LOGGER.info("Authentication rejected for myDomain endpoint", e);
                return response.status(401).send().then();
            });
    }

    private String resolveDomain(AuthenticatedRequest auth) {
        return auth.userInfo().claimByPropertyName("domain")
            .orElseGet(() -> {
                String email = auth.user();
                int atIndex = email.indexOf('@');
                if (atIndex >= 0) {
                    return email.substring(atIndex + 1);
                }
                throw new AccessForbiddenException(
                    "Cannot determine domain: no 'domain' claim and user '" + email + "' is not an email address");
            });
    }

    private interface NodePopulator {
        void populate(ObjectNode node);
    }

    private Mono<Void> serveJson(HttpServerResponse response, NodePopulator populator) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            populator.populate(node);
            byte[] json = MAPPER.writeValueAsBytes(node);
            return response.status(200)
                .header("Content-Type", "application/json")
                .sendByteArray(Mono.just(json))
                .then();
        } catch (AccessForbiddenException e) {
            return Mono.error(e);
        } catch (Exception e) {
            LOGGER.error("Failed to serialize response", e);
            return response.status(500).send().then();
        }
    }
}
