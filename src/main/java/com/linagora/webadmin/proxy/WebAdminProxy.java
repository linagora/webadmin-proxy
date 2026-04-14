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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@Singleton
public class WebAdminProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxy.class);
    private static final String HOST_HEADER = "Host";

    private final WebAdminProxyConfiguration configuration;
    private final HttpClient httpClient;
    private DisposableServer server;

    @Inject
    public WebAdminProxy(WebAdminProxyConfiguration configuration) {
        this.configuration = configuration;
        this.httpClient = HttpClient.create().baseUrl(configuration.webadminBackend());
    }

    public void start() {
        server = HttpServer.create()
            .port(configuration.port())
            .handle(this::forwardRequest)
            .bindNow();
        LOGGER.info("WebAdmin proxy listening on port {}", server.port());
    }

    public int getPort() {
        return server.port();
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
            LOGGER.info("WebAdmin proxy stopped");
        }
    }

    private Publisher<Void> forwardRequest(HttpServerRequest request, HttpServerResponse response) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.just(new byte[0]))
            .flatMap(payload -> dispatchToBackend(request, response, payload));
    }

    private Mono<Void> dispatchToBackend(HttpServerRequest request, HttpServerResponse response, byte[] payload) {
        return Mono.from(httpClient
            .headers(outgoing -> request.requestHeaders().forEach(entry -> {
                if (!entry.getKey().equalsIgnoreCase(HOST_HEADER)) {
                    outgoing.add(entry.getKey(), entry.getValue());
                }
            }))
            .request(request.method())
            .uri(request.uri())
            .send((req, out) -> out.sendByteArray(Mono.just(payload)))
            .response((res, body) -> writeBackendResponse(response, res, body)))
        .then();
    }

    private static NettyOutbound writeBackendResponse(HttpServerResponse response,
                                                       HttpClientResponse res,
                                                       ByteBufFlux body) {
        Mono<byte[]> aggregated = body.aggregate().asByteArray()
            .switchIfEmpty(Mono.just(new byte[0]));
        response.status(res.status());
        res.responseHeaders().forEach(entry -> response.header(entry.getKey(), entry.getValue()));
        return response.sendByteArray(aggregated);
    }
}
