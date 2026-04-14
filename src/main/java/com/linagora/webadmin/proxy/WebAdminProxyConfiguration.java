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
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
                                          Map<String, ClientConfiguration> clients,
                                          Optional<Integer> selfAdminPort,
                                          boolean selfAdminEnabled) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private OidcConfiguration oidcConfiguration;
        private Map<String, ClientConfiguration> clients = new HashMap<>();
        private Optional<Integer> selfAdminPort = Optional.empty();
        private boolean selfAdminEnabled = false;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder oidcConfiguration(OidcConfiguration oidcConfiguration) {
            this.oidcConfiguration = oidcConfiguration;
            return this;
        }

        public Builder clients(Map<String, ClientConfiguration> clients) {
            this.clients = clients;
            return this;
        }

        public Builder selfAdminPort(int port) {
            this.selfAdminPort = Optional.of(port);
            return this;
        }

        public Builder selfAdminEnabled() {
            this.selfAdminEnabled = true;
            return this;
        }

        public WebAdminProxyConfiguration build() {
            return new WebAdminProxyConfiguration(port, oidcConfiguration, clients, selfAdminPort, selfAdminEnabled);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyConfiguration.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\{ENV:([^}]+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static WebAdminProxyConfiguration from(File configFile) throws IOException {
        LOGGER.info("Loading configuration from {}", configFile.getAbsolutePath());
        JsonNode root = MAPPER.readTree(configFile);

        int port = Integer.parseInt(resolve(root.get("port").asText()));

        URL userInfoUrl = URI.create(resolve(root.get("oidc.userInfo.url").asText())).toURL();
        URL introspectUrl = URI.create(resolve(root.get("oidc.introspect.url").asText())).toURL();
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
            Map<String, String> expectedClaims = new HashMap<>();
            JsonNode claimsNode = clientNode.get("expected.claims");
            if (claimsNode != null) {
                claimsNode.fields().forEachRemaining(claim ->
                    expectedClaims.put(claim.getKey(), resolve(claim.getValue().asText())));
            }
            List<AllowedUrl> allowedUrls = new ArrayList<>();
            JsonNode allowedUrlsNode = clientNode.get("allowed.urls");
            if (allowedUrlsNode != null) {
                for (JsonNode urlNode : allowedUrlsNode) {
                    List<String> verbs = new ArrayList<>();
                    JsonNode verbsNode = urlNode.get("verb");
                    if (verbsNode != null) {
                        verbsNode.forEach(v -> verbs.add(v.asText()));
                    }
                    allowedUrls.add(new AllowedUrl(verbs, urlNode.get("endpoint").asText()));
                }
            }
            Map<String, UrlPatternRestriction> urlPatternRestrictions = new HashMap<>();
            JsonNode restrictionsNode = clientNode.get("url.patterns.restrictions");
            if (restrictionsNode != null) {
                restrictionsNode.fields().forEachRemaining(r -> {
                    String varName = r.getKey();
                    JsonNode restrictionNode = r.getValue();
                    String backingClaim = restrictionNode.get("backing.claim").asText();
                    UrlPatternRestriction.Operator operator = UrlPatternRestriction.Operator.valueOf(
                        restrictionNode.get("operator").asText());
                    urlPatternRestrictions.put(varName, new UrlPatternRestriction(backingClaim, operator));
                });
            }
            List<String> authorizedUsers = new ArrayList<>();
            JsonNode authorizedUsersNode = clientNode.get("authorized.users");
            if (authorizedUsersNode != null) {
                authorizedUsersNode.forEach(u -> authorizedUsers.add(resolve(u.asText())));
            }
            clients.put(entry.getKey(), new ClientConfiguration(
                resolve(clientNode.get("webadmin.backend").asText()),
                resolve(clientNode.get("webadmin.token").asText()),
                expectedClaims,
                allowedUrls,
                urlPatternRestrictions,
                authorizedUsers));
        }

        boolean selfAdminEnabled = Optional.ofNullable(root.get("self.webadmin.enabled"))
            .map(node -> Boolean.parseBoolean(resolve(node.asText())))
            .orElse(false);

        Builder builder = builder()
            .port(port)
            .oidcConfiguration(oidcConfiguration)
            .clients(clients);
        Optional.ofNullable(root.get("self.webadmin.port"))
            .map(node -> Integer.parseInt(resolve(node.asText())))
            .ifPresent(builder::selfAdminPort);
        if (selfAdminEnabled) {
            builder.selfAdminEnabled();
        }

        WebAdminProxyConfiguration config = builder.build();
        LOGGER.info("Configuration loaded: port={}, selfAdminEnabled={}, selfAdminPort={}, audience={}, userClaim={}, clients={}",
            port, selfAdminEnabled, config.selfAdminPort().map(String::valueOf).orElse("none"), audience, userClaim, clients.keySet());
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
