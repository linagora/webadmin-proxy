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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;

import io.restassured.response.Response;

class WebAdminProxyIntegrationTest {

    private ClientAndServer mockServer;
    private WebAdminProxyGuiceServer proxyServer;
    private MockServerClient mockClient;

    @BeforeEach
    void setUp() {
        mockServer = ClientAndServer.startClientAndServer(0);
        mockClient = new MockServerClient("localhost", mockServer.getLocalPort());

        WebAdminProxyConfiguration config = new WebAdminProxyConfiguration(
            0, "http://localhost:" + mockServer.getLocalPort());
        proxyServer = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
        proxyServer.start();
    }

    @AfterEach
    void tearDown() {
        proxyServer.stop();
        mockServer.stop();
    }

    @Test
    void shouldForwardGetRequest() {
        mockClient.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[\"example.com\"]"));

        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString()).contains("example.com");
    }

    @Test
    void shouldForwardPostRequest() {
        mockClient.when(request().withMethod("POST").withPath("/domains/example.com"))
            .respond(response().withStatusCode(204));

        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .post("/domains/example.com");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    void shouldForwardPutRequest() {
        mockClient.when(request().withMethod("PUT").withPath("/quota/count"))
            .respond(response().withStatusCode(204));

        Response response = given()
            .port(proxyServer.getPort())
            .body("1000")
        .when()
            .put("/quota/count");

        assertThat(response.statusCode()).isEqualTo(204);
        mockClient.verify(request()
            .withMethod("PUT")
            .withPath("/quota/count")
            .withBody("1000"));
    }

    @Test
    void shouldForwardDeleteRequest() {
        mockClient.when(request().withMethod("DELETE").withPath("/domains/example.com"))
            .respond(response().withStatusCode(204));

        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .delete("/domains/example.com");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @Test
    void shouldForwardRequestHeaders() {
        mockClient.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200).withBody("[]"));

        given()
            .port(proxyServer.getPort())
            .header("X-Custom-Header", "my-value")
            .header("X-Another-Header", "another-value")
        .when()
            .get("/domains")
        .then()
            .statusCode(200);

        mockClient.verify(request()
            .withMethod("GET")
            .withPath("/domains")
            .withHeader("X-Custom-Header", "my-value")
            .withHeader("X-Another-Header", "another-value"));
    }

    @Test
    void shouldForwardResponseHeaders() {
        mockClient.when(request().withMethod("GET").withPath("/domains"))
            .respond(response().withStatusCode(200)
                .withHeader("X-Custom-Response", "response-value")
                .withHeader("X-Another-Response", "another-value")
                .withBody("[]"));

        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .get("/domains");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("X-Custom-Response")).isEqualTo("response-value");
        assertThat(response.header("X-Another-Response")).isEqualTo("another-value");
    }

    @Test
    void shouldForwardQueryParameters() {
        mockClient.when(request().withMethod("GET").withPath("/users")
                .withQueryStringParameter("limit", "10"))
            .respond(response().withStatusCode(200).withBody("[\"bob@example.com\"]"));

        Response response = given()
            .port(proxyServer.getPort())
            .queryParam("limit", "10")
        .when()
            .get("/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString()).contains("bob@example.com");
    }

    @Test
    void shouldForwardStatusCodes() {
        mockClient.when(request().withMethod("GET").withPath("/nonexistent"))
            .respond(response().withStatusCode(404));

        Response response = given()
            .port(proxyServer.getPort())
        .when()
            .get("/nonexistent");

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
