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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.apache.james.util.DurationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record WebAdminProxyConfiguration(int port,
                                          OidcConfiguration oidcConfiguration,
                                          Map<String, ClientConfiguration> clients) {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyConfiguration.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\{ENV:([^}]+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static WebAdminProxyConfiguration from(File configFile) throws IOException {
        LOGGER.info("Loading configuration from {}", configFile.getAbsolutePath());
        JsonNode root = MAPPER.readTree(configFile);

        int port = Integer.parseInt(resolve(root.get("port").asText()));

        URL userInfoUrl = new URL(resolve(root.get("oidc.userInfo.url").asText()));
        URL introspectUrl = new URL(resolve(root.get("oidc.introspect.url").asText()));
        Optional<String> introspectCredentials = Optional.ofNullable(root.get("oidc.introspect.credentials"))
            .map(node -> resolve(node.asText()));
        IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, introspectCredentials);

        String audience = resolve(root.get("oidc.audience").asText());
        String userClaim = resolve(root.get("oidc.claim.authenticated.user").asText());
        Duration cacheExpiration = DurationParser.parse(resolve(root.get("oidc.token.cache.expiration").asText()), ChronoUnit.SECONDS);

        OidcConfiguration oidcConfiguration = new OidcConfiguration(
            userInfoUrl, introspectionEndpoint, audience, userClaim, cacheExpiration);

        Map<String, ClientConfiguration> clients = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> clientEntries = root.get("clients").fields();
        while (clientEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = clientEntries.next();
            JsonNode clientNode = entry.getValue();
            clients.put(entry.getKey(), new ClientConfiguration(
                resolve(clientNode.get("webadmin.backend").asText()),
                resolve(clientNode.get("webadmin.token").asText())));
        }

        WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(port, oidcConfiguration, clients);
        LOGGER.info("Configuration loaded: port={}, audience={}, userClaim={}, clients={}",
            port, audience, userClaim, clients.keySet());
        return config;
    }

    private static String resolve(String value) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String envValue = Optional.ofNullable(System.getenv(varName))
                .orElseThrow(() -> new IllegalArgumentException("Missing required environment variable: " + varName));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
