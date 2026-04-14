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
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
            CLIENT_ID, new ClientConfiguration("http://localhost:" + backendPort, WEBADMIN_TOKEN, Map.of()));

        return new WebAdminProxyConfiguration(0, oidcConfiguration, clients);
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
                    Map.of(REQUIRED_CLAIM_NAME, REQUIRED_CLAIM_VALUE)));

            WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(0, oidcConfiguration, clients);
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
}
