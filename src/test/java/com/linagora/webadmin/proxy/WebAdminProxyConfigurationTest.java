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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WebAdminProxyConfigurationTest {

    @TempDir
    Path tempDir;

    private File writeConfig(String json) throws IOException {
        File file = tempDir.resolve("configuration.json").toFile();
        Files.writeString(file.toPath(), json);
        return file;
    }

    // Minimal valid config used as a base in most tests
    private static final String MINIMAL = """
        {
          "port": "8001",
          "oidc.userInfo.url": "http://lemonldap/userinfo",
          "oidc.introspect.url": "http://lemonldap/introspect",
          "oidc.audience": "webadmin-proxy",
          "oidc.claim.authenticated.user": "email",
          "oidc.token.cache.expiration": "60s",
          "clients": [
            {
              "my-client": {
                "webadmin.backend": "http://james:8000",
                "webadmin.token": "secret"
              }
            }
          ]
        }
        """;

    @Nested
    class TopLevelFields {

        @Test
        void shouldParsePort() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.port()).isEqualTo(8001);
        }

        @Test
        void shouldParseOidcUserInfoUrl() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().userInfoUrl().toString())
                .isEqualTo("http://lemonldap/userinfo");
        }

        @Test
        void shouldParseOidcIntrospectUrl() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().introspectionEndpoint().getUrl().toString())
                .isEqualTo("http://lemonldap/introspect");
        }

        @Test
        void shouldParseAudienceAsString() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().audiences()).containsExactly("webadmin-proxy");
        }

        @Test
        void shouldParseAudienceAsArray() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": ["aud-a", "aud-b"],
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [ { "c": { "webadmin.backend": "http://james:8000", "webadmin.token": "t" } } ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.oidcConfiguration().audiences()).containsExactly("aud-a", "aud-b");
        }

        @Test
        void shouldParseSingleElementAudienceArray() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": ["webadmin-proxy"],
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [ { "c": { "webadmin.backend": "http://james:8000", "webadmin.token": "t" } } ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.oidcConfiguration().audiences()).containsExactly("webadmin-proxy");
        }

        @Test
        void shouldParseUserClaim() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().userClaim()).isEqualTo("email");
        }

        @Test
        void shouldParseCacheExpiration() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().tokenCacheExpiration()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        void shouldParseIntrospectCredentials() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.introspect.credentials": "Bearer my-secret",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [ { "c": { "webadmin.backend": "http://james:8000", "webadmin.token": "t" } } ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.oidcConfiguration().introspectionEndpoint().getAuthorizationHeader())
                .contains("Bearer my-secret");
        }

        @Test
        void introspectCredentialsShouldBeAbsentWhenNotConfigured() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.oidcConfiguration().introspectionEndpoint().getAuthorizationHeader())
                .isEmpty();
        }
    }

    @Nested
    class SelfAdmin {

        @Test
        void selfAdminEnabledShouldDefaultToFalse() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.selfAdminEnabled()).isFalse();
        }

        @Test
        void shouldParseSelfAdminEnabled() throws Exception {
            String json = MINIMAL.replace("\"clients\"", "\"self.webadmin.enabled\": \"true\", \"clients\"");
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.selfAdminEnabled()).isTrue();
        }

        @Test
        void selfAdminPortShouldBeAbsentWhenNotConfigured() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.selfAdminPort()).isEmpty();
        }

        @Test
        void shouldParseSelfAdminPort() throws Exception {
            String json = MINIMAL.replace("\"clients\"", "\"self.webadmin.port\": \"8002\", \"clients\"");
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.selfAdminPort()).contains(8002);
        }
    }

    @Nested
    class ClientParsing {

        @Test
        void shouldParseClientBackendAndToken() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            ClientConfiguration client = config.clientsForId("my-client").get(0);
            assertThat(client.webadminBackend()).isEqualTo("http://james:8000");
            assertThat(client.webadminToken()).isEqualTo("secret");
        }

        @Test
        void shouldParseMultipleClients() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    { "client-a": { "webadmin.backend": "http://james-a:8000", "webadmin.token": "token-a" } },
                    { "client-b": { "webadmin.backend": "http://james-b:8000", "webadmin.token": "token-b" } }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("client-a")).hasSize(1);
            assertThat(config.clientsForId("client-b")).hasSize(1);
            assertThat(config.clientsForId("client-a").get(0).webadminBackend()).isEqualTo("http://james-a:8000");
            assertThat(config.clientsForId("client-b").get(0).webadminBackend()).isEqualTo("http://james-b:8000");
        }

        @Test
        void shouldParseDuplicateClientId() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    { "my-client": { "webadmin.backend": "http://james-a:8000", "webadmin.token": "token-a",
                                     "authorized.users": ["alice@example.com"] } },
                    { "my-client": { "webadmin.backend": "http://james-b:8000", "webadmin.token": "token-b",
                                     "authorized.users": ["bob@example.com"] } }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client")).hasSize(2);
            assertThat(config.clientsForId("my-client").get(0).webadminBackend()).isEqualTo("http://james-a:8000");
            assertThat(config.clientsForId("my-client").get(1).webadminBackend()).isEqualTo("http://james-b:8000");
        }

        @Test
        void expectedClaimsShouldBeEmptyWhenAbsent() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.clientsForId("my-client").get(0).expectedClaims()).isEmpty();
        }

        @Test
        void shouldParseExpectedClaims() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "expected.claims": { "admin": "1", "role": "superadmin" }
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).expectedClaims())
                .containsEntry("admin", "1")
                .containsEntry("role", "superadmin");
        }

        @Test
        void authorizedUsersShouldBeEmptyWhenAbsent() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.clientsForId("my-client").get(0).authorizedUsers()).isEmpty();
        }

        @Test
        void expectedScopesShouldBeEmptyWhenAbsent() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.clientsForId("my-client").get(0).expectedScopes()).isEmpty();
        }

        @Test
        void shouldParseExpectedScopes() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "expected.scopes": ["scopea", "scopeb"]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).expectedScopes())
                .containsExactlyInAnyOrder("scopea", "scopeb");
        }

        @Test
        void shouldParseAuthorizedUsers() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "authorized.users": ["alice@example.com", "bob@example.com"]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).authorizedUsers())
                .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
        }
    }

    @Nested
    class AllowedUrlsParsing {

        @Test
        void allowedUrlsShouldBeEmptyWhenAbsent() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.clientsForId("my-client").get(0).allowedUrls()).isEmpty();
        }

        @Test
        void shouldParseAllowedUrlEndpoint() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          { "endpoint": "/domains/{domain}/users" }
                        ]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).allowedUrls())
                .hasSize(1)
                .first()
                .satisfies(rule -> {
                    assertThat(rule.endpointPattern()).isEqualTo("/domains/{domain}/users");
                    assertThat(rule.verbs()).isEmpty();
                });
        }

        @Test
        void shouldParseAllowedUrlWithVerbs() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          { "verb": ["GET", "PUT"], "endpoint": "/users" }
                        ]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).allowedUrls().get(0).verbs())
                .containsExactlyInAnyOrder("GET", "PUT");
        }

        @Test
        void shouldParseMultipleAllowedUrls() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          { "verb": ["GET"], "endpoint": "/domains/{domain}/users" },
                          { "endpoint": "/domains/{domain}/aliases/*" }
                        ]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).allowedUrls()).hasSize(2);
        }
    }

    @Nested
    class UrlPatternRestrictionsParsing {

        @Test
        void urlPatternRestrictionsShouldBeEmptyWhenAbsent() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(MINIMAL));
            assertThat(config.clientsForId("my-client").get(0).urlPatternRestrictions()).isEmpty();
        }

        @Test
        void shouldParseEqualsOperator() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "url.patterns.restrictions": {
                          "domain": { "backing.claim": "domain", "operator": "EQUALS" }
                        }
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            UrlPatternRestriction restriction = config.clientsForId("my-client").get(0).urlPatternRestrictions().get("domain");
            assertThat(restriction.backingClaim()).isEqualTo("domain");
            assertThat(restriction.operator()).isEqualTo(UrlPatternRestriction.Operator.EQUALS);
        }

        @Test
        void shouldParseHasDomainOperator() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "url.patterns.restrictions": {
                          "domain": { "backing.claim": "email", "operator": "HAS_DOMAIN" }
                        }
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            UrlPatternRestriction restriction = config.clientsForId("my-client").get(0).urlPatternRestrictions().get("domain");
            assertThat(restriction.backingClaim()).isEqualTo("email");
            assertThat(restriction.operator()).isEqualTo(UrlPatternRestriction.Operator.HAS_DOMAIN);
        }
    }

    @Nested
    class EnvVarInterpolation {

        @Test
        void shouldSubstituteEnvVar() throws Exception {
            // HOME is always set on Linux; we inject it into a config field that accepts free text
            String home = System.getenv("HOME");
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "{ENV:HOME}",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [ { "c": { "webadmin.backend": "http://james:8000", "webadmin.token": "t" } } ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.oidcConfiguration().audiences()).containsExactly(home);
        }

        @Test
        void shouldFailOnMissingEnvVar() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "{ENV:THIS_VAR_DOES_NOT_EXIST_XYZ_12345}",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [ { "c": { "webadmin.backend": "http://james:8000", "webadmin.token": "t" } } ]
                }
                """;
            assertThatThrownBy(() -> WebAdminProxyConfiguration.from(writeConfig(json)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("THIS_VAR_DOES_NOT_EXIST_XYZ_12345");
        }
    }

    @Nested
    class AllowedUrlsInclude {

        private File writeConfigWithInclude(String includeUri) throws IOException {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          {"denied": true, "verb": ["POST"], "endpoint": "/domains/{domain}?action=deleteData"},
                          {"include": "%s"}
                        ]
                      }
                    }
                  ]
                }
                """.formatted(includeUri);
            return writeConfig(json);
        }

        @Test
        void shouldExpandClasspathInclude() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(
                writeConfigWithInclude("classpath://test-allowed-urls.json"));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls).hasSize(4);
            assertThat(urls.get(0).endpointPattern()).isEqualTo("/domains/{domain}?action=deleteData");
            assertThat(urls.get(0).isDenied()).isTrue();
            assertThat(urls.get(1).endpointPattern()).isEqualTo("/domains/{domain}");
            assertThat(urls.get(2).verbs()).containsExactly("GET");
            assertThat(urls.get(3).isDenied()).isTrue();
        }

        @Test
        void shouldExpandFileRelativeInclude() throws Exception {
            Path includeFile = tempDir.resolve("extra-urls.json");
            Files.writeString(includeFile, """
                [
                  {"endpoint": "/domains/{domain}"},
                  {"verb": ["GET"], "endpoint": "/users/%@{domain}"}
                ]
                """);
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(
                writeConfigWithInclude("file://" + includeFile.toAbsolutePath()));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls).hasSize(3);
            assertThat(urls.get(0).endpointPattern()).isEqualTo("/domains/{domain}?action=deleteData");
            assertThat(urls.get(1).endpointPattern()).isEqualTo("/domains/{domain}");
            assertThat(urls.get(2).endpointPattern()).isEqualTo("/users/%@{domain}");
        }

        @Test
        void shouldExpandFileAbsoluteInclude() throws Exception {
            Path includeFile = tempDir.resolve("extra-urls-abs.json");
            Files.writeString(includeFile, """
                [{"endpoint": "/tasks/*"}]
                """);
            String absolutePath = includeFile.toAbsolutePath().toString();
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(
                writeConfigWithInclude("file:///" + absolutePath.substring(1)));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls).hasSize(2);
            assertThat(urls.get(1).endpointPattern()).isEqualTo("/tasks/*");
        }

        @Test
        void shouldPreserveOrderInlineThenIncluded() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(
                writeConfigWithInclude("classpath://test-allowed-urls.json"));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls.get(0).endpointPattern()).isEqualTo("/domains/{domain}?action=deleteData");
            assertThat(urls.get(1).endpointPattern()).isEqualTo("/domains/{domain}");
        }

        @Test
        void shouldLoadCalendarBaselineProfile() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          {"include": "classpath://functional-admin-calendar-baseline.json"}
                        ]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls).hasSize(13);
            assertThat(urls.stream().map(u -> u.endpointPattern()))
                .contains("/domains/{domain}", "/calendars/%@{domain}", "/tasks/{domain}/*");
        }

        @Test
        void shouldLoadMailBaselineProfile() throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          {"include": "classpath://functional-admin-mail-baseline.json"}
                        ]
                      }
                    }
                  ]
                }
                """;
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            assertThat(urls).hasSize(24);
            assertThat(urls.stream().map(u -> u.endpointPattern()))
                .contains("/domains/{domain}", "/users/%@{domain}", "/tasks/{domain}/*");
        }

        @Test
        void shouldExpandNestedIncludes() throws Exception {
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(
                writeConfigWithInclude("classpath://test-allowed-urls-fragment.json"));
            var urls = config.clientsForId("my-client").get(0).allowedUrls();
            // 1 inline denied + /healthcheck (from fragment) + 3 from test-allowed-urls.json (nested include)
            assertThat(urls).hasSize(5);
            assertThat(urls.get(0).endpointPattern()).isEqualTo("/domains/{domain}?action=deleteData");
            assertThat(urls.get(1).endpointPattern()).isEqualTo("/healthcheck");
            assertThat(urls.get(2).endpointPattern()).isEqualTo("/domains/{domain}");
            assertThat(urls.get(3).endpointPattern()).isEqualTo("/users/%@{domain}");
            assertThat(urls.get(4).endpointPattern()).isEqualTo("/domains/{domain}/quota");
            assertThat(urls.get(4).isDenied()).isTrue();
        }

        @Test
        void shouldThrowWhenClasspathResourceNotFound() throws Exception {
            assertThatThrownBy(() -> WebAdminProxyConfiguration.from(
                    writeConfigWithInclude("classpath://nonexistent.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent.json");
        }
    }

    @Nested
    class PredefinedProfiles {

        static Stream<String> predefinedProfileNames() throws IOException {
            try (var files = Files.list(Path.of("src/main/resources"))) {
                return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .toList()
                    .stream();
            }
        }

        @ParameterizedTest
        @MethodSource("predefinedProfileNames")
        void shouldParseWithoutError(String filename) throws Exception {
            String json = """
                {
                  "port": "8001",
                  "oidc.userInfo.url": "http://lemonldap/userinfo",
                  "oidc.introspect.url": "http://lemonldap/introspect",
                  "oidc.audience": "webadmin-proxy",
                  "oidc.claim.authenticated.user": "email",
                  "oidc.token.cache.expiration": "60s",
                  "clients": [
                    {
                      "my-client": {
                        "webadmin.backend": "http://james:8000",
                        "webadmin.token": "secret",
                        "allowed.urls": [
                          {"include": "classpath://%s"}
                        ]
                      }
                    }
                  ]
                }
                """.formatted(filename);
            WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(writeConfig(json));
            assertThat(config.clientsForId("my-client").get(0).allowedUrls()).isNotEmpty();
        }
    }
}
