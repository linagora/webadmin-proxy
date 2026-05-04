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
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.jwt.DefaultCheckTokenClient;
import org.apache.james.jwt.introspection.TokenIntrospectionResponse;
import org.apache.james.jwt.userinfo.UserinfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

public class OidcTokenResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OidcTokenResolver.class);

    private final DefaultCheckTokenClient checkTokenClient;
    private final OidcConfiguration oidcConfiguration;
    private final List<Map.Entry<String, ClientConfiguration>> clients;

    @Inject
    public OidcTokenResolver(DefaultCheckTokenClient checkTokenClient,
                              OidcConfiguration oidcConfiguration,
                              WebAdminProxyConfiguration configuration) {
        this.checkTokenClient = checkTokenClient;
        this.oidcConfiguration = oidcConfiguration;
        this.clients = configuration.clients();
    }

    public Mono<AuthenticatedRequest> resolve(String token) {
        return Mono.zip(
                Mono.from(checkTokenClient.userInfo(oidcConfiguration.userInfoUrl(), token)),
                Mono.from(checkTokenClient.introspect(oidcConfiguration.introspectionEndpoint(), token)))
            .flatMap(tuple -> buildAuthenticatedRequest(tuple.getT1(), tuple.getT2()))
            .onErrorMap(e -> !(e instanceof OidcAuthenticationException) && !(e instanceof AccessForbiddenException),
                e -> new OidcAuthenticationException("OIDC endpoint error", e));
    }

    private Mono<AuthenticatedRequest> buildAuthenticatedRequest(UserinfoResponse userInfo,
                                                                   TokenIntrospectionResponse introspect) {
        if (!introspect.active()) {
            return Mono.error(new OidcAuthenticationException("Token is not active"));
        }

        if (!audienceMatches(introspect)) {
            return Mono.error(new OidcAuthenticationException(
                "Invalid audience. Expected one of: " + oidcConfiguration.audiences()));
        }

        String user = userInfo.claimByPropertyName(oidcConfiguration.userClaim())
            .orElseThrow(() -> new OidcAuthenticationException(
                "Missing required claim '" + oidcConfiguration.userClaim() + "' in userinfo response"));

        String clientId = introspect.clientId()
            .orElseThrow(() -> new OidcAuthenticationException(
                "Missing 'client_id' in introspect response (RFC 7662)"));

        List<ClientConfiguration> entriesForClientId = clients.stream()
            .filter(e -> e.getKey().equals(clientId))
            .map(Map.Entry::getValue)
            .toList();

        if (entriesForClientId.isEmpty()) {
            return Mono.error(new OidcAuthenticationException("Unknown client_id: " + clientId));
        }

        ClientConfiguration clientConfiguration = entriesForClientId.stream()
            .filter(config -> isUserAuthorized(config, user) && claimsMatch(config, userInfo) && scopesMatch(config, introspect))
            .findFirst()
            .orElseThrow(() -> new AccessForbiddenException(
                "No applicable configuration for user '" + user + "' with client '" + clientId + "'"));

        Optional<String> sessionId = Optional.ofNullable(introspect.json().get("sid")).map(JsonNode::asText)
            .or(() -> userInfo.claimByPropertyName("sid"));
        return Mono.just(new AuthenticatedRequest(user, clientId, clientConfiguration, userInfo, sessionId));
    }

    private boolean isUserAuthorized(ClientConfiguration config, String user) {
        List<String> authorizedUsers = config.authorizedUsers();
        return authorizedUsers.isEmpty() || authorizedUsers.contains(user);
    }

    private boolean scopesMatch(ClientConfiguration config, TokenIntrospectionResponse introspect) {
        List<String> requiredScopes = config.expectedScopes();
        if (requiredScopes.isEmpty()) {
            return true;
        }
        List<String> tokenScopes = introspect.scope()
            .map(s -> List.of(s.split("\\s+")))
            .orElse(List.of());
        return tokenScopes.containsAll(requiredScopes);
    }

    private boolean claimsMatch(ClientConfiguration config, UserinfoResponse userInfo) {
        for (Map.Entry<String, String> claim : config.expectedClaims().entrySet()) {
            Optional<String> actual = userInfo.claimByPropertyName(claim.getKey());
            if (actual.isEmpty() || !claim.getValue().equals(actual.get())) {
                return false;
            }
        }
        return true;
    }

    private boolean audienceMatches(TokenIntrospectionResponse introspect) {
        List<String> expected = oidcConfiguration.audiences();
        JsonNode audNode = introspect.json().get("aud");
        if (audNode == null) {
            return false;
        }
        if (audNode.isArray()) {
            for (JsonNode node : audNode) {
                if (expected.contains(node.asText())) {
                    return true;
                }
            }
            return false;
        }
        return expected.contains(audNode.asText());
    }
}
