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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import org.apache.james.jwt.DefaultCheckTokenClient;
import org.apache.james.jwt.introspection.TokenIntrospectionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

public class OidcTokenResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OidcTokenResolver.class);
    private static final String SID_CLAIM = "sid";

    private final DefaultCheckTokenClient checkTokenClient;
    private final UserinfoClient userinfoClient;
    private final OidcConfiguration oidcConfiguration;
    private final List<Map.Entry<String, ClientConfiguration>> clients;

    @Inject
    public OidcTokenResolver(DefaultCheckTokenClient checkTokenClient,
                              UserinfoClient userinfoClient,
                              OidcConfiguration oidcConfiguration,
                              WebAdminProxyConfiguration configuration) {
        this.checkTokenClient = checkTokenClient;
        this.userinfoClient = userinfoClient;
        this.oidcConfiguration = oidcConfiguration;
        this.clients = configuration.clients();
    }

    public Mono<AuthenticatedRequest> resolve(String token) {
        return Mono.zip(
                userinfoClient.userInfo(oidcConfiguration.userInfoUrl(), token),
                Mono.from(checkTokenClient.introspect(oidcConfiguration.introspectionEndpoint(), token)))
            .flatMap(tuple -> buildAuthenticatedRequest(tuple.getT1(), tuple.getT2()))
            .onErrorMap(e -> !(e instanceof OidcAuthenticationException) && !(e instanceof AccessForbiddenException),
                e -> new OidcAuthenticationException("OIDC endpoint error", e));
    }

    private Mono<AuthenticatedRequest> buildAuthenticatedRequest(Userinfo userInfo,
                                                                   TokenIntrospectionResponse introspect) {
        if (!introspect.active()) {
            return Mono.error(new OidcAuthenticationException("Token is not active"));
        }

        if (!audienceMatches(introspect)) {
            return Mono.error(new OidcAuthenticationException(
                "Invalid audience. Expected one of: " + oidcConfiguration.audiences()));
        }

        String user = userInfo.claim(oidcConfiguration.userClaim())
            .filter(claim -> !claim.isBlank())
            .orElseThrow(() -> new OidcAuthenticationException(
                "Missing required claim '" + oidcConfiguration.userClaim() + "' in userinfo response"));

        String clientId = TextualJson.textualField(introspect.json(), "client_id")
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

        Optional<String> sessionId = TextualJson.textualField(introspect.json(), SID_CLAIM)
            .or(() -> userInfo.claim(SID_CLAIM));
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

    private boolean claimsMatch(ClientConfiguration config, Userinfo userInfo) {
        return config.expectedClaims().entrySet().stream()
            .allMatch(expectedClaim -> userInfo.claim(expectedClaim.getKey())
                .filter(expectedClaim.getValue()::equals)
                .isPresent());
    }

    private boolean audienceMatches(TokenIntrospectionResponse introspect) {
        List<String> expected = oidcConfiguration.audiences();
        return Optional.ofNullable(introspect.json().get("aud"))
            .stream()
            .flatMap(OidcTokenResolver::audienceElements)
            .flatMap(audience -> TextualJson.textual(audience).stream())
            .anyMatch(expected::contains);
    }

    private static Stream<JsonNode> audienceElements(JsonNode audNode) {
        if (audNode.isArray()) {
            return StreamSupport.stream(audNode.spliterator(), false);
        }
        return Stream.of(audNode);
    }
}
