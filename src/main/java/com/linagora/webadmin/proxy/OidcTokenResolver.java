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
    private final Map<String, ClientConfiguration> clients;

    @Inject
    public OidcTokenResolver(DefaultCheckTokenClient checkTokenClient,
                              OidcConfiguration oidcConfiguration,
                              Map<String, ClientConfiguration> clients) {
        this.checkTokenClient = checkTokenClient;
        this.oidcConfiguration = oidcConfiguration;
        this.clients = clients;
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
                "Invalid audience. Expected: " + oidcConfiguration.audience()));
        }

        String user = userInfo.claimByPropertyName(oidcConfiguration.userClaim())
            .orElseThrow(() -> new OidcAuthenticationException(
                "Missing required claim '" + oidcConfiguration.userClaim() + "' in userinfo response"));

        String clientId = introspect.clientId()
            .orElseThrow(() -> new OidcAuthenticationException(
                "Missing 'client_id' in introspect response (RFC 7662)"));

        ClientConfiguration clientConfiguration = Optional.ofNullable(clients.get(clientId))
            .orElseThrow(() -> new OidcAuthenticationException("Unknown client_id: " + clientId));

        validateExpectedClaims(clientConfiguration, userInfo);

        Optional<String> sessionId = Optional.ofNullable(introspect.json().get("sid")).map(JsonNode::asText);
        return Mono.just(new AuthenticatedRequest(user, clientId, clientConfiguration, userInfo, sessionId));
    }

    private void validateExpectedClaims(ClientConfiguration clientConfiguration, UserinfoResponse userInfo) {
        clientConfiguration.expectedClaims().forEach((claimName, expectedValue) -> {
            String actualValue = userInfo.claimByPropertyName(claimName)
                .orElseThrow(() -> new AccessForbiddenException(
                    "Missing required claim '" + claimName + "' in userinfo response"));
            if (!expectedValue.equals(actualValue)) {
                throw new AccessForbiddenException(
                    "Claim '" + claimName + "' value does not match: expected '" + expectedValue + "'");
            }
        });
    }

    private boolean audienceMatches(TokenIntrospectionResponse introspect) {
        String expected = oidcConfiguration.audience();
        JsonNode audNode = introspect.json().get("aud");
        if (audNode == null) {
            return false;
        }
        if (audNode.isArray()) {
            for (JsonNode node : audNode) {
                if (expected.equals(node.asText())) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(audNode.asText());
    }
}
