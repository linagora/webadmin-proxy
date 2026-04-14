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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.webadmin.PublicRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import spark.Service;

@Singleton
public class BackchannelLogoutRoutes implements PublicRoutes {

    public static final String PATH = "/backchannel-logout";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String LOGOUT_TOKEN_PARAM = "logout_token";
    private static final String SID_CLAIM = "sid";
    private static final Logger LOGGER = LoggerFactory.getLogger(BackchannelLogoutRoutes.class);

    private final OidcTokenCache tokenCache;
    private final ObjectMapper objectMapper;

    @Inject
    public BackchannelLogoutRoutes(OidcTokenCache tokenCache) {
        this.tokenCache = tokenCache;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getBasePath() {
        return PATH;
    }

    @Override
    public void define(Service service) {
        service.post(PATH, (req, res) -> {
            String contentType = req.contentType();
            if (contentType == null || !contentType.startsWith(FORM_CONTENT_TYPE)) {
                res.status(415);
                return "Unsupported Media Type: expected application/x-www-form-urlencoded";
            }

            String logoutToken = req.queryParams(LOGOUT_TOKEN_PARAM);
            if (logoutToken == null || logoutToken.isEmpty()) {
                res.status(400);
                return "Missing logout_token parameter";
            }

            try {
                String sid = extractSid(logoutToken);
                LOGGER.info("Backchannel logout: invalidating session sid={}", sid);
                tokenCache.invalidateBySid(sid).block();
                res.status(200);
                return "";
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid logout token", e);
                res.status(400);
                return "Invalid logout_token: " + e.getMessage();
            }
        });
    }

    private String extractSid(String logoutToken) {
        String[] parts = logoutToken.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("JWT must have at least 2 parts");
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(addPadding(parts[1]));
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            return Optional.ofNullable(payload.get(SID_CLAIM))
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("Missing 'sid' claim in logout token"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to decode logout token", e);
        }
    }

    private static String addPadding(String base64url) {
        int pad = base64url.length() % 4;
        if (pad == 2) {
            return base64url + "==";
        }
        if (pad == 3) {
            return base64url + "=";
        }
        return base64url;
    }

}
