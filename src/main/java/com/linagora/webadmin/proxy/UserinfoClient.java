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

import java.io.IOException;
import java.net.URL;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Fetches the OIDC userinfo answer and keeps it as JSON, so that the JSON type of each claim can be checked.
 *
 * <p>James' {@code UserinfoResponse} only exposes claims through {@code asText()}, which hides that type.
 */
public class UserinfoClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.create();

    public Mono<Userinfo> userInfo(URL userInfoUrl, String token) {
        return httpClient
            .headers(headers -> headers
                .add(HttpHeaderNames.AUTHORIZATION, "Bearer " + token)
                .add(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON))
            .get()
            .uri(userInfoUrl.toString())
            .responseSingle((response, body) -> {
                if (!response.status().equals(HttpResponseStatus.OK)) {
                    return Mono.error(new OidcAuthenticationException(
                        "Userinfo endpoint answered " + response.status().code()));
                }
                return body.asByteArray()
                    .switchIfEmpty(Mono.error(() -> new OidcAuthenticationException("Empty userinfo response")))
                    .flatMap(UserinfoClient::parse);
            });
    }

    private static Mono<Userinfo> parse(byte[] body) {
        try {
            JsonNode json = MAPPER.readTree(body);
            if (json == null || !json.isObject()) {
                return Mono.error(new OidcAuthenticationException("Userinfo response is not a JSON object"));
            }
            return Mono.just(new Userinfo(json));
        } catch (IOException e) {
            return Mono.error(new OidcAuthenticationException("Invalid userinfo response", e));
        }
    }
}
