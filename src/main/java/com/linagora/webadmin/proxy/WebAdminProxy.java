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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.lifecycle.api.Startable;
import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCBuilder;
import org.apache.james.util.ReactorUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpResponseStatus;
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
public class WebAdminProxy implements Startable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxy.class);
    private static final String HOST_HEADER = "Host";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final Set<String> RESERVED_HEADERS = Set.of(HOST_HEADER, AUTHORIZATION_HEADER);
    private static final String ALLOWED_CORS_HEADERS = "Content-Type, Authorization, Accept, I-KNOW-WHAT-I-M-DOING";
    private static final String ALLOWED_URLS_PATH = "/.proxy/allowed/urls";
    private static final String WHOAMI_PATH = "/.proxy/whoami";
    private static final String MY_DOMAIN_PATH = "/.proxy/myDomain";
    private static final String BACKEND_UNAUTHORIZED_MESSAGE =
        "Webadmin backend rejected the proxy credentials: check the 'webadmin.token' configuration";

    private final WebAdminProxyConfiguration configuration;
    private final OidcTokenCache tokenCache;
    private final AllowedUrlsHandler allowedUrlsHandler;
    private final ProxyInfoHandler proxyInfoHandler;
    private final Map<String, HttpClient> backendClients;
    private final Metric requestsMetric;
    private DisposableServer server;

    @Inject
    public WebAdminProxy(WebAdminProxyConfiguration configuration, OidcTokenCache tokenCache,
                          AllowedUrlsHandler allowedUrlsHandler, ProxyInfoHandler proxyInfoHandler,
                          MetricFactory metricFactory) {
        this.configuration = configuration;
        this.tokenCache = tokenCache;
        this.allowedUrlsHandler = allowedUrlsHandler;
        this.proxyInfoHandler = proxyInfoHandler;
        this.requestsMetric = metricFactory.generate("webadmin.proxy.requests");
        this.backendClients = configuration.clients().stream()
            .collect(Collectors.toMap(
                e -> e.getValue().webadminBackend(),
                e -> HttpClient.create().baseUrl(e.getValue().webadminBackend()),
                (a, b) -> a));
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

    public void awaitStop() {
        server.onDispose().block();
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
            LOGGER.info("WebAdmin proxy stopped");
        }
    }

    private Publisher<Void> forwardRequest(HttpServerRequest request, HttpServerResponse response) {
        addCorsHeaders(request, response);

        if ("OPTIONS".equalsIgnoreCase(request.method().name())) {
            return response.status(204).send().then();
        }

        String authHeader = request.requestHeaders().get(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return response.status(401).send();
        }
        String token = authHeader.substring("Bearer ".length());

        if ("GET".equalsIgnoreCase(request.method().name())) {
            String path = request.fullPath();
            if (ALLOWED_URLS_PATH.equals(path)) {
                return allowedUrlsHandler.handle(response, token);
            }
            if (WHOAMI_PATH.equals(path)) {
                return proxyInfoHandler.handleWhoami(response, token);
            }
            if (MY_DOMAIN_PATH.equals(path)) {
                return proxyInfoHandler.handleMyDomain(response, token);
            }
        }

        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.just(new byte[0]))
            .flatMap(payload -> tokenCache.resolve(token)
                .flatMap(auth -> auth.checkUserAuthorized()
                    .then(checkUrlAccess(auth, request))
                    .then(dispatchToBackend(request, response, payload, auth))
                    .contextWrite(ReactorUtils.context("request",
                        MDCBuilder.create()
                            .addToContext(MDCBuilder.USER, auth.user())
                            .addToContext("clientId", auth.clientId()))))
                .onErrorResume(AccessForbiddenException.class, e ->
                    ReactorUtils.logAsMono(() -> LOGGER.info("Access forbidden", e))
                        .then(response.status(403).send().then()))
                .onErrorResume(OidcAuthenticationException.class, e ->
                    ReactorUtils.logAsMono(() -> LOGGER.info("Authentication rejected", e))
                        .then(response.status(401).send().then())));
    }

    private void addCorsHeaders(HttpServerRequest request, HttpServerResponse response) {
        List<String> allowedOrigins = configuration.corsAllowOrigins();
        if (allowedOrigins.isEmpty()) {
            return;
        }
        String requestOrigin = request.requestHeaders().get("Origin");
        if (requestOrigin == null) {
            return;
        }
        response.header("Access-Control-Allow-Methods", "DELETE, GET, PATCH, POST, PUT");
        response.header("Access-Control-Allow-Headers", ALLOWED_CORS_HEADERS);
        if (allowedOrigins.contains("*")) {
            response.header("Access-Control-Allow-Origin", "*");
        } else if (allowedOrigins.contains(requestOrigin)) {
            response.header("Access-Control-Allow-Origin", requestOrigin);
            response.header("Access-Control-Allow-Credentials", "true");
            response.header("Vary", "Origin");
        }
    }

    private Mono<Void> checkUrlAccess(AuthenticatedRequest auth, HttpServerRequest request) {
        List<AllowedUrl> allowedUrls = auth.clientConfiguration().allowedUrls();
        if (allowedUrls.isEmpty()) {
            return Mono.empty();
        }
        String uri = request.uri();
        String method = request.method().name();
        Optional<AllowedUrl> firstMatch = allowedUrls.stream()
            .filter(rule -> rule.match(method, uri).isPresent())
            .findFirst();
        if (firstMatch.isEmpty()) {
            return Mono.error(new AccessForbiddenException(
                "URL not allowed for client '" + auth.clientId() + "': " + method + " " + uri));
        }
        AllowedUrl matchedRule = firstMatch.get();
        if (matchedRule.isDenied()) {
            return Mono.error(new AccessForbiddenException(
                "URL explicitly denied for client '" + auth.clientId() + "': " + method + " " + uri));
        }
        Map<String, String> capturedVars = matchedRule.match(method, uri).get();
        return validatePatternRestrictions(auth, capturedVars);
    }

    private Mono<Void> validatePatternRestrictions(AuthenticatedRequest auth, Map<String, String> capturedVars) {
        for (Map.Entry<String, UrlPatternRestriction> entry : auth.clientConfiguration().urlPatternRestrictions().entrySet()) {
            String varName = entry.getKey();
            UrlPatternRestriction restriction = entry.getValue();
            String urlVarValue = capturedVars.get(varName);
            if (urlVarValue == null) {
                continue;
            }
            Optional<String> claimOpt = auth.userInfo().claimByPropertyName(restriction.backingClaim());
            if (claimOpt.isEmpty()) {
                return Mono.error(new AccessForbiddenException(
                    "Missing required claim '" + restriction.backingClaim() + "' for URL validation"));
            }
            try {
                String expectedValue = restriction.operator().extractExpectedValue(claimOpt.get());
                if (!expectedValue.equals(urlVarValue)) {
                    return Mono.error(new AccessForbiddenException(
                        "URL variable '" + varName + "' does not match claim constraint"));
                }
            } catch (AccessForbiddenException e) {
                return Mono.error(e);
            }
        }
        return Mono.empty();
    }

    private Mono<Void> dispatchToBackend(HttpServerRequest request, HttpServerResponse response,
                                          byte[] payload, AuthenticatedRequest auth) {
        requestsMetric.increment();
        HttpClient backendClient = backendClients.get(auth.clientConfiguration().webadminBackend());
        String webadminToken = auth.clientConfiguration().webadminToken();
        return ReactorUtils.logAsMono(() -> LOGGER.info("Proxying request: method={}, endpoint={}",
                request.method().name(), request.uri()))
            .then(Mono.from(backendClient
            .headers(outgoing -> {
                request.requestHeaders().forEach(entry -> {
                    if (RESERVED_HEADERS.stream().noneMatch(h -> h.equalsIgnoreCase(entry.getKey()))) {
                        outgoing.add(entry.getKey(), entry.getValue());
                    }
                });
                outgoing.set(AUTHORIZATION_HEADER, "Bearer " + webadminToken);
            })
            .request(request.method())
            .uri(request.uri())
            .send((req, out) -> out.sendByteArray(Mono.just(payload)))
            .response((res, body) -> writeBackendResponse(response, res, body))))
        .then();
    }

    private static NettyOutbound writeBackendResponse(HttpServerResponse response,
                                                       HttpClientResponse res,
                                                       ByteBufFlux body) {
        Mono<byte[]> aggregated = body.aggregate().asByteArray()
            .switchIfEmpty(Mono.just(new byte[0]));
        if (res.status().code() == HttpResponseStatus.UNAUTHORIZED.code()) {
            return rejectBackendUnauthorized(response, res, aggregated);
        }
        response.status(res.status());
        res.responseHeaders().forEach(entry -> response.header(entry.getKey(), entry.getValue()));
        return response.sendByteArray(aggregated);
    }

    /**
     * The proxy holds full webadmin access by design: a 401 from the backend can only mean the
     * configured {@code webadmin.token} is wrong. Forwarding it verbatim would make frontends believe
     * their own OIDC session expired, so it is reported as a proxy-side misconfiguration instead.
     */
    private static NettyOutbound rejectBackendUnauthorized(HttpServerResponse response,
                                                           HttpClientResponse res,
                                                           Mono<byte[]> backendBody) {
        response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        return response.sendByteArray(backendBody
            .doOnNext(body -> LOGGER.error("Backend rejected the configured webadmin token (401) for {} {}: {}",
                res.method().name(), res.uri(), new String(body, StandardCharsets.UTF_8)))
            .map(body -> BACKEND_UNAUTHORIZED_MESSAGE.getBytes(StandardCharsets.UTF_8)));
    }
}
