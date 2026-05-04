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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ClientConfiguration(String webadminBackend, String webadminToken,
                                   Map<String, String> expectedClaims, List<AllowedUrl> allowedUrls,
                                   Map<String, UrlPatternRestriction> urlPatternRestrictions,
                                   List<String> authorizedUsers, List<String> expectedScopes) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String webadminBackend;
        private String webadminToken;
        private Map<String, String> expectedClaims = new HashMap<>();
        private List<AllowedUrl> allowedUrls = new ArrayList<>();
        private Map<String, UrlPatternRestriction> urlPatternRestrictions = new HashMap<>();
        private List<String> authorizedUsers = new ArrayList<>();
        private List<String> expectedScopes = new ArrayList<>();

        public Builder webadminBackend(String webadminBackend) {
            this.webadminBackend = webadminBackend;
            return this;
        }

        public Builder webadminToken(String webadminToken) {
            this.webadminToken = webadminToken;
            return this;
        }

        public Builder expectedClaims(Map<String, String> expectedClaims) {
            this.expectedClaims = new HashMap<>(expectedClaims);
            return this;
        }

        public Builder allowedUrls(List<AllowedUrl> allowedUrls) {
            this.allowedUrls = new ArrayList<>(allowedUrls);
            return this;
        }

        public Builder urlPatternRestrictions(Map<String, UrlPatternRestriction> urlPatternRestrictions) {
            this.urlPatternRestrictions = new HashMap<>(urlPatternRestrictions);
            return this;
        }

        public Builder authorizedUsers(List<String> authorizedUsers) {
            this.authorizedUsers = new ArrayList<>(authorizedUsers);
            return this;
        }

        public Builder expectedScopes(List<String> expectedScopes) {
            this.expectedScopes = new ArrayList<>(expectedScopes);
            return this;
        }

        public ClientConfiguration build() {
            Objects.requireNonNull(webadminBackend, "webadminBackend is required");
            Objects.requireNonNull(webadminToken, "webadminToken is required");
            return new ClientConfiguration(
                webadminBackend, webadminToken,
                Map.copyOf(expectedClaims), List.copyOf(allowedUrls),
                Map.copyOf(urlPatternRestrictions), List.copyOf(authorizedUsers),
                List.copyOf(expectedScopes));
        }
    }
}
