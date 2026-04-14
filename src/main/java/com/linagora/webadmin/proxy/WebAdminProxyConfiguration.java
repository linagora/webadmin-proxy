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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record WebAdminProxyConfiguration(int port, String webadminBackend) {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyConfiguration.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\{ENV:([^}]+)\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static WebAdminProxyConfiguration from(File configFile) throws IOException {
        LOGGER.info("Loading configuration from {}", configFile.getAbsolutePath());
        JsonNode root = MAPPER.readTree(configFile);

        int port = Integer.parseInt(resolve(root.get("port").asText()));
        String webadminBackend = resolve(root.get("webadmin.backend").asText());

        WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(port, webadminBackend);
        LOGGER.info("WebAdmin proxy configuration: port={}, webadmin.backend={}", config.port(), config.webadminBackend());
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
