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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
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
                                          List<Map.Entry<String, ClientConfiguration>> clients,
                                          Optional<Integer> selfAdminPort,
                                          boolean selfAdminEnabled,
                                          List<String> corsAllowOrigins) {

    public List<ClientConfiguration> clientsForId(String clientId) {
        return clients.stream()
            .filter(e -> e.getKey().equals(clientId))
            .map(Map.Entry::getValue)
            .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private OidcConfiguration oidcConfiguration;
        private List<Map.Entry<String, ClientConfiguration>> clients = new ArrayList<>();
        private Optional<Integer> selfAdminPort = Optional.empty();
        private boolean selfAdminEnabled = false;
        private List<String> corsAllowOrigins = new ArrayList<>();

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder oidcConfiguration(OidcConfiguration oidcConfiguration) {
            this.oidcConfiguration = oidcConfiguration;
            return this;
        }

        public Builder clients(List<Map.Entry<String, ClientConfiguration>> clientsList) {
            this.clients = new ArrayList<>(clientsList);
            return this;
        }

        public Builder clients(Map<String, ClientConfiguration> clientsMap) {
            clientsMap.forEach((id, config) ->
                this.clients.add(new AbstractMap.SimpleImmutableEntry<>(id, config)));
            return this;
        }

        public Builder addClient(String clientId, ClientConfiguration config) {
            this.clients.add(new AbstractMap.SimpleImmutableEntry<>(clientId, config));
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

        public Builder corsAllowOrigins(List<String> origins) {
            this.corsAllowOrigins = new ArrayList<>(origins);
            return this;
        }

        public WebAdminProxyConfiguration build() {
            return new WebAdminProxyConfiguration(port, oidcConfiguration, List.copyOf(clients), selfAdminPort, selfAdminEnabled, List.copyOf(corsAllowOrigins));
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyConfiguration.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\{ENV:([^}]+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static WebAdminProxyConfiguration from(File configFile) throws IOException {
        LOGGER.info("Loading configuration from {}", configFile.getAbsolutePath());
        JsonNode root = MAPPER.readTree(configFile);

        int port = Integer.parseInt(resolve(root.get("port").asText()));
        OidcConfiguration oidcConfiguration = parseOidcConfiguration(root);
        List<Map.Entry<String, ClientConfiguration>> clients = parseClients(root);

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
        parseCorsOrigins(root).ifPresent(builder::corsAllowOrigins);

        WebAdminProxyConfiguration config = builder.build();
        LOGGER.info("Configuration loaded: port={}, selfAdminEnabled={}, selfAdminPort={}, audiences={}, userClaim={}, clients={}",
            port, selfAdminEnabled, config.selfAdminPort().map(String::valueOf).orElse("none"),
            oidcConfiguration.audiences(), oidcConfiguration.userClaim(),
            config.clients().stream().map(Map.Entry::getKey).toList());
        return config;
    }

    private static OidcConfiguration parseOidcConfiguration(JsonNode root) throws IOException {
        URL userInfoUrl = URI.create(resolve(root.get("oidc.userInfo.url").asText())).toURL();
        URL introspectUrl = URI.create(resolve(root.get("oidc.introspect.url").asText())).toURL();
        Optional<String> introspectCredentials = Optional.ofNullable(root.get("oidc.introspect.credentials"))
            .map(node -> resolve(node.asText()));
        IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, introspectCredentials);
        List<String> audiences = parseStringList(root.get("oidc.audience"));
        String userClaim = resolve(root.get("oidc.claim.authenticated.user").asText());
        Duration cacheExpiration = DurationParser.parse(resolve(root.get("oidc.token.cache.expiration").asText()), ChronoUnit.SECONDS);
        return new OidcConfiguration(userInfoUrl, introspectionEndpoint, audiences, userClaim, cacheExpiration);
    }

    private static List<Map.Entry<String, ClientConfiguration>> parseClients(JsonNode root) {
        List<Map.Entry<String, ClientConfiguration>> clients = new ArrayList<>();
        for (JsonNode clientElement : root.get("clients")) {
            clientElement.fields().forEachRemaining(entry ->
                clients.add(parseClientEntry(entry.getKey(), entry.getValue())));
        }
        return clients;
    }

    private static Map.Entry<String, ClientConfiguration> parseClientEntry(String clientId, JsonNode clientNode) {
        return new AbstractMap.SimpleImmutableEntry<>(clientId, ClientConfiguration.builder()
            .webadminBackend(resolve(clientNode.get("webadmin.backend").asText()))
            .webadminToken(resolve(clientNode.get("webadmin.token").asText()))
            .expectedClaims(parseExpectedClaims(clientNode))
            .allowedUrls(parseAllowedUrls(clientNode))
            .urlPatternRestrictions(parseUrlPatternRestrictions(clientNode))
            .authorizedUsers(parseAuthorizedUsers(clientNode))
            .expectedScopes(parseExpectedScopes(clientNode))
            .build());
    }

    private static Map<String, String> parseExpectedClaims(JsonNode clientNode) {
        Map<String, String> expectedClaims = new HashMap<>();
        JsonNode claimsNode = clientNode.get("expected.claims");
        if (claimsNode != null) {
            claimsNode.fields().forEachRemaining(claim ->
                expectedClaims.put(claim.getKey(), resolve(claim.getValue().asText())));
        }
        return expectedClaims;
    }

    private static List<AllowedUrl> parseAllowedUrls(JsonNode clientNode) {
        List<AllowedUrl> allowedUrls = new ArrayList<>();
        JsonNode allowedUrlsNode = clientNode.get("allowed.urls");
        if (allowedUrlsNode != null) {
            for (JsonNode urlNode : allowedUrlsNode) {
                List<String> verbs = new ArrayList<>();
                JsonNode verbsNode = urlNode.get("verb");
                if (verbsNode != null) {
                    verbsNode.forEach(v -> verbs.add(v.asText()));
                }
                boolean denied = Optional.ofNullable(urlNode.get("denied"))
                    .map(JsonNode::asBoolean)
                    .orElse(false);
                allowedUrls.add(new AllowedUrl(verbs, urlNode.get("endpoint").asText(), denied));
            }
        }
        return allowedUrls;
    }

    private static Map<String, UrlPatternRestriction> parseUrlPatternRestrictions(JsonNode clientNode) {
        Map<String, UrlPatternRestriction> urlPatternRestrictions = new HashMap<>();
        JsonNode restrictionsNode = clientNode.get("url.patterns.restrictions");
        if (restrictionsNode != null) {
            restrictionsNode.fields().forEachRemaining(r -> {
                JsonNode restrictionNode = r.getValue();
                urlPatternRestrictions.put(r.getKey(), new UrlPatternRestriction(
                    restrictionNode.get("backing.claim").asText(),
                    UrlPatternRestriction.Operator.valueOf(restrictionNode.get("operator").asText())));
            });
        }
        return urlPatternRestrictions;
    }

    private static List<String> parseAuthorizedUsers(JsonNode clientNode) {
        List<String> authorizedUsers = new ArrayList<>();
        JsonNode authorizedUsersNode = clientNode.get("authorized.users");
        if (authorizedUsersNode != null) {
            authorizedUsersNode.forEach(u -> authorizedUsers.add(resolve(u.asText())));
        }
        return authorizedUsers;
    }

    private static List<String> parseExpectedScopes(JsonNode clientNode) {
        List<String> expectedScopes = new ArrayList<>();
        JsonNode scopesNode = clientNode.get("expected.scopes");
        if (scopesNode != null) {
            scopesNode.forEach(s -> expectedScopes.add(resolve(s.asText())));
        }
        return expectedScopes;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(resolve(n.asText())));
        } else {
            result.add(resolve(node.asText()));
        }
        return List.copyOf(result);
    }

    private static Optional<List<String>> parseCorsOrigins(JsonNode root) {
        JsonNode corsNode = root.get("cors.allow.origin");
        if (corsNode == null) {
            return Optional.empty();
        }
        return Optional.of(parseStringList(corsNode));
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
