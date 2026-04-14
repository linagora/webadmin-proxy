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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.APPLICATION_JSON;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.webadmin.proxy.UrlPatternRestriction.Operator;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.verify.VerificationTimes;

import io.restassured.response.Response;

class WebAdminProxyIntegrationTest {

    static final String VALID_TOKEN = "valid-opaque-token";
    static final String WEBADMIN_TOKEN = "webadmin-backend-secret";
    static final String CLIENT_ID = "my-client";
    static final String USER_EMAIL = "user@example.com";
    static final String AUDIENCE = "webadmin";

    private ClientAndServer oidcMockServer;
    private ClientAndServer backendMockServer;
    private MockServerClient oidcMock;
    private MockServerClient backendMock;
    private WebAdminProxyGuiceServer proxyServer;

    @BeforeEach
    void setUp() throws Exception {
        oidcMockServer = ClientAndServer.startClientAndServer(0);
        backendMockServer = ClientAndServer.startClientAndServer(0);
        oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
        backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

        WebAdminProxyConfiguration config = buildConfig();
        proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
        proxyServer.start();
    }

    private WebAdminProxyConfiguration buildConfig() throws Exception {
        int oidcPort = oidcMockServer.getLocalPort();
        int backendPort = backendMockServer.getLocalPort();

        URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
        URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
        IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
        OidcConfiguration oidcConfiguration = new OidcConfiguration(
            userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));

        Map<String, ClientConfiguration> clients = Map.of(
            CLIENT_ID, new ClientConfiguration("http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), List.of(), Map.of(), List.of()));

        return new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
    }

    @AfterEach
    void tearDown() {
        proxyServer.stop();
        oidcMockServer.stop();
        backendMockServer.stop();
    }

    private void stubValidToken() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));
    }

    // --- Happy path ---

    @Test
    void shouldForwardGetRequest() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[\"example.com\"]"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString()).contains("example.com");
    }

    @Test
    void shouldForwardPostRequest() {
        stubValidToken();
        backendMock.when(request().withMethod("POST").withPath("/domains/example.com"))
            .respond(response().withStatusCode(204));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .post("/domains/example.com");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    void shouldForwardPutRequestWithBody() {
        stubValidToken();
        backendMock.when(request().withMethod("PUT").withPath("/quota/count"))
            .respond(response().withStatusCode(204));

        given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .body("1000")
        .when()
            .put("/quota/count")
        .then()
            .statusCode(204);

        backendMock.verify(request()
            .withMethod("PUT")
            .withPath("/quota/count")
            .withBody("1000"));
    }

    @Test
    void shouldForwardDeleteRequest() {
        stubValidToken();
        backendMock.when(request().withMethod("DELETE").withPath("/domains/example.com"))
            .respond(response().withStatusCode(204));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .delete("/domains/example.com");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    // --- Token substitution ---

    @Test
    void shouldSubstituteAuthorizationHeaderWithWebadminToken() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains")
        .then()
            .statusCode(200);

        backendMock.verify(request()
            .withMethod("GET")
            .withPath("/domains")
            .withHeader("Authorization", "Bearer " + WEBADMIN_TOKEN));
    }

    // --- Request/response passthrough ---

    @Test
    void shouldForwardArbitraryRequestHeaders() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .header("X-Custom-Header", "my-value")
            .header("X-Another-Header", "another-value")
        .when()
            .get("/domains")
        .then()
            .statusCode(200);

        backendMock.verify(request()
            .withMethod("GET")
            .withPath("/domains")
            .withHeader("X-Custom-Header", "my-value")
            .withHeader("X-Another-Header", "another-value"));
    }

    @Test
    void shouldForwardResponseHeaders() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200)
                .withHeader("X-Custom-Response", "response-value")
                .withHeader("X-Another-Response", "another-value")
                .withBody("[]"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("X-Custom-Response")).isEqualTo("response-value");
        assertThat(response.header("X-Another-Response")).isEqualTo("another-value");
    }

    @Test
    void shouldForwardQueryParameters() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("limit", "10"))
            .respond(response().withStatusCode(200).withBody("[\"bob@example.com\"]"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
            .queryParam("limit", "10")
        .when()
            .get("/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString()).contains("bob@example.com");
    }

    @Test
    void shouldForwardBackendStatusCodes() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/nonexistent"))
            .respond(response().withStatusCode(404));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/nonexistent");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // --- 401 scenarios ---

    @Test
    void shouldReturn401WhenNoAuthorizationHeader() {
        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenBasicAuth() {
        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Basic dXNlcjpwYXNz")
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenTokenIsInactive() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":false}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenAudienceMismatch() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"wrong-audience\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenAudienceMissing() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenAudienceNotInArray() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":[\"other\",\"wrong\"],\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldAcceptAudienceAsArray() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":[\"other\",\"" + AUDIENCE + "\"],\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldReturn401WhenMissingUserClaim() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"sub\":\"some-subject\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenMissingClientId() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenUnknownClientId() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"unknown-client\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenUserinfoEndpointFails() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(401));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void shouldReturn401WhenIntrospectEndpointFails() {
        oidcMock.when(request().withMethod("POST").withPath("/introspect"))
            .respond(response().withStatusCode(500));
        oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
            .respond(response().withStatusCode(200)
                .withContentType(APPLICATION_JSON)
                .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

        Response response = given()
            .port(proxyServer.getPort())
            .header("Authorization", "Bearer " + VALID_TOKEN)
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // --- Token cache ---

    @Test
    void shouldCacheTokenInfoAcrossRequests() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        given().port(proxyServer.getPort()).header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains").then().statusCode(200);
        given().port(proxyServer.getPort()).header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains").then().statusCode(200);

        oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(1));
        oidcMock.verify(request().withPath("/userinfo"), VerificationTimes.exactly(1));
    }

    @Test
    void shouldNotShareCacheEntriesAcrossDistinctTokens() {
        stubValidToken();
        backendMock.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        given().port(proxyServer.getPort()).header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains").then().statusCode(200);
        given().port(proxyServer.getPort()).header("Authorization", "Bearer another-token")
            .when().get("/domains").then().statusCode(200);

        oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(2));
        oidcMock.verify(request().withPath("/userinfo"), VerificationTimes.exactly(2));
    }

    @Nested
    class UrlRestrictions {

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @BeforeEach
        void setUp() throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());
        }

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void startProxyWith(List<AllowedUrl> allowedUrls) throws Exception {
            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), allowedUrls, Map.of(), List.of()));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        private void stubValidToken() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));
        }

        @Test
        void shouldAllowRequestMatchingExactEndpoint() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains")));
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenEndpointNotInAllowList() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains")));
            stubValidToken();

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users")
                .then().statusCode(403);
        }

        @Test
        void shouldAllowOnlyConfiguredVerbs() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of("GET"), "/domains")));
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().delete("/domains")
                .then().statusCode(403);
        }

        @Test
        void shouldAllowAllVerbsWhenVerbListIsEmpty() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains")));
            stubValidToken();
            backendMock.when(request().withMethod("DELETE").withPath("/domains"))
                .respond(response().withStatusCode(204));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().delete("/domains")
                .then().statusCode(204);
        }

        @Test
        void shouldMatchTemplateVariable() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains/{domain}/aliases")));
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains/example.com/aliases"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/aliases")
                .then().statusCode(200);
        }

        @Test
        void shouldNotMatchAcrossSegmentsWithTemplateVariable() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains/{domain}/aliases")));
            stubValidToken();

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/other/aliases")
                .then().statusCode(403);
        }

        @Test
        void shouldMatchWildcard() throws Exception {
            startProxyWith(List.of(new AllowedUrl(List.of(), "/domains/{domain}/aliases/*")));
            stubValidToken();
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/aliases/bob@example.com")
                .then().statusCode(200);
        }

        @Test
        void shouldMatchMultipleRules() throws Exception {
            startProxyWith(List.of(
                new AllowedUrl(List.of("GET"), "/domains"),
                new AllowedUrl(List.of(), "/users/{user}")));
            stubValidToken();
            backendMock.when(request()).respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().delete("/users/bob@example.com")
                .then().statusCode(200);
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().post("/domains")
                .then().statusCode(403);
        }

        @Test
        void shouldAllowAllUrlsWhenAllowedUrlsIsEmpty() throws Exception {
            startProxyWith(List.of());
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/anything"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/anything")
                .then().statusCode(200);
        }
    }

    @Nested
    class UrlPatternRestrictions {

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @BeforeEach
        void setUp() {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());
        }

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void startProxyWith(Map<String, UrlPatternRestriction> restrictions, List<AllowedUrl> allowedUrls) throws Exception {
            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), allowedUrls, restrictions, List.of()));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        private void stubIntrospect() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        }

        private void stubUserinfo(String body) {
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200).withContentType(APPLICATION_JSON).withBody(body));
        }

        @Test
        void equalsOperatorShouldAllowWhenClaimMatchesUrlVariable() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("domain", Operator.EQUALS)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"" + USER_EMAIL + "\",\"domain\":\"example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(200);
        }

        @Test
        void equalsOperatorShouldReturn403WhenClaimDoesNotMatchUrlVariable() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("domain", Operator.EQUALS)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"" + USER_EMAIL + "\",\"domain\":\"other.com\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(403);
        }

        @Test
        void hasDomainOperatorShouldAllowWhenEmailDomainMatchesUrlVariable() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"bob@example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(200);
        }

        @Test
        void hasDomainOperatorShouldReturn403WhenEmailDomainDoesNotMatchUrlVariable() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"bob@other.com\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(403);
        }

        @Test
        void shouldReturn403WhenBackingClaimIsMissing() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("domain", Operator.EQUALS)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"" + USER_EMAIL + "\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(403);
        }

        @Test
        void shouldReturn403WhenEmailIsNotValidForHasDomain() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/users")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"not-an-email\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/users")
                .then().statusCode(403);
        }

        @Test
        void restrictionShouldNotApplyWhenVariableNotInMatchedPattern() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("domain", Operator.EQUALS)),
                List.of(new AllowedUrl(List.of(), "/quota/count")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"" + USER_EMAIL + "\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("{}"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/quota/count")
                .then().statusCode(200);
        }

        @Test
        void hasDomainOperatorShouldWorkWithQueryParameter() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of("GET"), "/users?domain={domain}")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"bob@example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users?domain=example.com")
                .then().statusCode(200);

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users?domain=other.com")
                .then().statusCode(403);

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users")
                .then().statusCode(403);
        }

        @Test
        void hasDomainOperatorShouldWorkWithWildcardPattern() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/domains/{domain}/aliases/*")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"bob@example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/example.com/aliases/bob@example.com")
                .then().statusCode(200);
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains/other.com/aliases/bob@example.com")
                .then().statusCode(403);
        }
    }

    @Nested
    class EmailLocalPartWildcard {

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @BeforeEach
        void setUp() {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());
        }

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void startProxyWith(Map<String, UrlPatternRestriction> restrictions, List<AllowedUrl> allowedUrls) throws Exception {
            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), allowedUrls, restrictions, List.of()));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        private void stubIntrospect() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
        }

        private void stubUserinfo(String body) {
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200).withContentType(APPLICATION_JSON).withBody(body));
        }

        @Test
        void shouldAllowUserEndpointWhenEmailDomainMatches() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/users/%@{domain}/*")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"alice@example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/bob@example.com/mailboxes")
                .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenEmailDomainDoesNotMatchTargetUser() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/users/%@{domain}/*")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"alice@example.com\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/bob@other.com/mailboxes")
                .then().statusCode(403);
        }

        @Test
        void shouldReturn403WhenUrlHasNoAtSignInUserSegment() throws Exception {
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/users/%@{domain}/*")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"alice@example.com\"}");

            // URL pattern requires email format: "bob" (no @) should not match %@{domain}
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/bob/mailboxes")
                .then().statusCode(403);
        }

        @Test
        void localPartShouldNotBeExposedAsCapturedVariable() throws Exception {
            // % is anonymous: only {domain} should be captured and validated
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("email", Operator.HAS_DOMAIN)),
                List.of(new AllowedUrl(List.of(), "/users/%@{domain}/*")));
            stubIntrospect();
            stubUserinfo("{\"email\":\"alice@example.com\"}");
            backendMock.when(request().withMethod("GET"))
                .respond(response().withStatusCode(200).withBody("[]"));

            // Different local parts, same domain — all should be allowed
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/alice@example.com/mailboxes")
                .then().statusCode(200);
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/charlie.d@example.com/quota")
                .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenBackingClaimMissingForPercentPattern() throws Exception {
            // Use a separate backing claim (not the auth claim) so auth passes but restriction fails
            startProxyWith(
                Map.of("domain", new UrlPatternRestriction("domain_claim", Operator.EQUALS)),
                List.of(new AllowedUrl(List.of(), "/users/%@{domain}/*")));
            stubIntrospect();
            // email present (auth succeeds), but domain_claim is absent (restriction fails → 403)
            stubUserinfo("{\"email\":\"alice@example.com\"}");

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/users/bob@example.com/mailboxes")
                .then().statusCode(403);
        }
    }

    @Nested
    class ExpectedClaimsValidation {

        static final String RESTRICTED_CLIENT_ID = "restricted-client";
        static final String REQUIRED_CLAIM_NAME = "admin";
        static final String REQUIRED_CLAIM_VALUE = "1";

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @BeforeEach
        void setUp() throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();

            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));

            Map<String, ClientConfiguration> clients = Map.of(
                RESTRICTED_CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort,
                    WEBADMIN_TOKEN,
                    Map.of(REQUIRED_CLAIM_NAME, REQUIRED_CLAIM_VALUE),
                    List.of(),
                    Map.of(),
                    List.of()));

            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void stubIntrospect() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + RESTRICTED_CLIENT_ID + "\"}"));
        }

        @Test
        void shouldAllowRequestWhenClaimMatches() {
            stubIntrospect();
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"1\"}"));
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            Response response = given()
                .port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when()
                .get("/domains");

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        void shouldReturn403WhenRequiredClaimIsMissing() {
            stubIntrospect();
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

            Response response = given()
                .port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when()
                .get("/domains");

            assertThat(response.statusCode()).isEqualTo(403);
        }

        @Test
        void shouldReturn403WhenClaimValueDoesNotMatch() {
            stubIntrospect();
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"0\"}"));

            Response response = given()
                .port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when()
                .get("/domains");

            assertThat(response.statusCode()).isEqualTo(403);
        }

        @Test
        void shouldReturn403WhenClaimIsPresentWithDifferentCase() {
            stubIntrospect();
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"True\"}"));

            Response response = given()
                .port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when()
                .get("/domains");

            assertThat(response.statusCode()).isEqualTo(403);
        }

        @Test
        void shouldReturn401WhenTokenIsInvalidEvenWithExpectedClaims() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":false}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"1\"}"));

            Response response = given()
                .port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when()
                .get("/domains");

            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    @Nested
    class AuthorizedUsers {

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void startProxyWith(List<String> authorizedUsers) throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), List.of(), Map.of(), authorizedUsers));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        private void stubValidToken() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));
        }

        @Test
        void shouldAllowRequestWhenUserIsInAuthorizedList() throws Exception {
            startProxyWith(List.of(USER_EMAIL, "other@example.com"));
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenUserIsNotInAuthorizedList() throws Exception {
            startProxyWith(List.of("other@example.com", "another@example.com"));
            stubValidToken();

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(403);
        }

        @Test
        void shouldAllowAllUsersWhenAuthorizedListIsEmpty() throws Exception {
            startProxyWith(List.of());
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(200);
        }

        private void startProxyWith(List<String> authorizedUsers, Map<String, String> expectedClaims) throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, expectedClaims, List.of(), Map.of(), authorizedUsers));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        @Test
        void shouldAllowWhenUserIsInListAndHasRequiredClaim() throws Exception {
            startProxyWith(List.of(USER_EMAIL), Map.of("admin", "1"));
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"1\"}"));
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenUserIsInListButRequiredClaimIsMissing() throws Exception {
            startProxyWith(List.of(USER_EMAIL), Map.of("admin", "1"));
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(403);
        }

        @Test
        void shouldReturn403WhenUserHasRequiredClaimButIsNotInList() throws Exception {
            startProxyWith(List.of("other@example.com"), Map.of("admin", "1"));
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\",\"admin\":\"1\"}"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(403);
        }

        private void startProxyWith(List<String> authorizedUsers, List<AllowedUrl> allowedUrls) throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration(
                    "http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), allowedUrls, Map.of(), authorizedUsers));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.empty());
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        @Test
        void shouldAllowWhenUserIsInListAndUrlIsAllowed() throws Exception {
            startProxyWith(List.of(USER_EMAIL), List.of(new AllowedUrl(List.of("GET"), "/domains")));
            stubValidToken();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(200);
        }

        @Test
        void shouldReturn403WhenUserIsInListButUrlIsNotAllowed() throws Exception {
            startProxyWith(List.of(USER_EMAIL), List.of(new AllowedUrl(List.of("GET"), "/domains")));
            stubValidToken();

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().delete("/domains")
            .then().statusCode(403);
        }

        @Test
        void shouldReturn403WhenUserIsNotInListEvenIfUrlWouldBeAllowed() throws Exception {
            startProxyWith(List.of("other@example.com"), List.of(new AllowedUrl(List.of("GET"), "/domains")));
            stubValidToken();

            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
            .when().get("/domains")
            .then().statusCode(403);
        }
    }

    @Nested
    class BackchannelLogout {

        static final String SESSION_ID = "session-abc-123";

        private ClientAndServer oidcMockServer;
        private ClientAndServer backendMockServer;
        private MockServerClient oidcMock;
        private MockServerClient backendMock;
        private WebAdminProxyGuiceServer proxyServer;

        @BeforeEach
        void setUp() throws Exception {
            oidcMockServer = ClientAndServer.startClientAndServer(0);
            backendMockServer = ClientAndServer.startClientAndServer(0);
            oidcMock = new MockServerClient("localhost", oidcMockServer.getLocalPort());
            backendMock = new MockServerClient("localhost", backendMockServer.getLocalPort());

            int oidcPort = oidcMockServer.getLocalPort();
            int backendPort = backendMockServer.getLocalPort();
            URL userInfoUrl = new URL("http://localhost:" + oidcPort + "/userinfo");
            URL introspectUrl = new URL("http://localhost:" + oidcPort + "/introspect");
            IntrospectionEndpoint introspectionEndpoint = new IntrospectionEndpoint(introspectUrl, Optional.empty());
            OidcConfiguration oidcConfiguration = new OidcConfiguration(
                userInfoUrl, introspectionEndpoint, AUDIENCE, "email", Duration.ofSeconds(60));
            Map<String, ClientConfiguration> clients = Map.of(
                CLIENT_ID, new ClientConfiguration("http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of(), List.of(), Map.of(), List.of()));
            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients, Optional.of(0));
            proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
            proxyServer.start();
        }

        @AfterEach
        void tearDown() {
            proxyServer.stop();
            oidcMockServer.stop();
            backendMockServer.stop();
        }

        private void stubValidTokenWithSid() {
            oidcMock.when(request().withMethod("POST").withPath("/introspect"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"active\":true,\"aud\":\"" + AUDIENCE + "\",\"client_id\":\"" + CLIENT_ID + "\",\"sid\":\"" + SESSION_ID + "\"}"));
            oidcMock.when(request().withMethod("GET").withPath("/userinfo"))
                .respond(response().withStatusCode(200)
                    .withContentType(APPLICATION_JSON)
                    .withBody("{\"email\":\"" + USER_EMAIL + "\"}"));
        }

        /** Builds a minimal unsigned JWT: header.payload. */
        static String buildLogoutToken(String sid) {
            String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sid\":\"" + sid + "\"}").getBytes(StandardCharsets.UTF_8));
            return header + "." + payload + ".";
        }

        @Test
        void shouldReturn200OnValidBackchannelLogout() {
            stubValidTokenWithSid();

            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("logout_token=" + buildLogoutToken(SESSION_ID))
            .when().post("/backchannel-logout")
            .then().statusCode(200);
        }

        @Test
        void shouldInvalidateCachedTokenAfterBackchannelLogout() throws Exception {
            stubValidTokenWithSid();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            // Warm up the cache with a valid request
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);

            // Token was cached — OIDC endpoints called exactly once
            oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(1));

            // Backchannel logout invalidates the session
            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("logout_token=" + buildLogoutToken(SESSION_ID))
            .when().post("/backchannel-logout")
            .then().statusCode(200);

            // Next request must re-validate with OIDC
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);

            oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(2));
        }

        @Test
        void shouldNotInvalidateTokensFromDifferentSession() throws Exception {
            stubValidTokenWithSid();
            backendMock.when(request().withMethod("GET").withPath("/domains"))
                .respond(response().withStatusCode(200).withBody("[]"));

            // Warm up the cache
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);

            oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(1));

            // Logout with a different session id
            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("logout_token=" + buildLogoutToken("other-session-id"))
            .when().post("/backchannel-logout")
            .then().statusCode(200);

            // Cache should still be intact — no new OIDC call
            given().port(proxyServer.getPort())
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .when().get("/domains")
                .then().statusCode(200);

            oidcMock.verify(request().withPath("/introspect"), VerificationTimes.exactly(1));
        }

        @Test
        void shouldReturn415WhenContentTypeIsNotFormUrlEncoded() {
            given().port(proxyServer.getAdminPort())
                .contentType("application/json")
                .body("{\"logout_token\":\"some-token\"}")
            .when().post("/backchannel-logout")
            .then().statusCode(415);
        }

        @Test
        void shouldReturn400WhenLogoutTokenIsMissing() {
            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("other_param=value")
            .when().post("/backchannel-logout")
            .then().statusCode(400);
        }

        @Test
        void shouldReturn400WhenLogoutTokenIsNotAJwt() {
            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("logout_token=not-a-jwt")
            .when().post("/backchannel-logout")
            .then().statusCode(400);
        }

        @Test
        void shouldReturn400WhenLogoutTokenHasNoSidClaim() {
            String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"user\"}".getBytes(StandardCharsets.UTF_8));
            String token = header + "." + payload + ".";

            given().port(proxyServer.getAdminPort())
                .contentType("application/x-www-form-urlencoded")
                .body("logout_token=" + token)
            .when().post("/backchannel-logout")
            .then().statusCode(400);
        }
    }
}
