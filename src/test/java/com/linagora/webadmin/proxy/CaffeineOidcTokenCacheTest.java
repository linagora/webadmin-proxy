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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

class CaffeineOidcTokenCacheTest {

    private static final String TOKEN_1 = "secret-token-1";
    private static final String TOKEN_2 = "secret-token-2";
    private static final String SID = "session-1";
    private static final Duration EXPIRATION = Duration.ofMinutes(5);

    private final Logger logger = (Logger) LoggerFactory.getLogger(CaffeineOidcTokenCache.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;
    private WebAdminProxyConfiguration configuration;
    private CaffeineOidcTokenCache testee;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        configuration = WebAdminProxyConfiguration.builder()
            .oidcConfiguration(new OidcConfiguration(null, null, List.of(), "email", EXPIRATION))
            .build();
        testee = new CaffeineOidcTokenCache(new SidlessTokenResolver(configuration), configuration);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void resolveShouldNeverLogTheToken() {
        testee.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).block();
        testee.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_2).block();

        assertThat(appender.list)
            .isNotEmpty()
            .map(ILoggingEvent::getFormattedMessage)
            .noneMatch(message -> message.contains(TOKEN_1) || message.contains(TOKEN_2));
    }

    @Test
    void missingSidShouldBeWarnedOnlyOnce() {
        testee.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).block();
        testee.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_2).block();

        assertThat(appender.list)
            .filteredOn(event -> event.getLevel() == Level.WARN)
            .hasSize(1);
    }

    @Test
    void invalidateBySidShouldDropATokenWhoseResolutionIsInFlight() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CompletableFuture<Void> idpAnswer = new CompletableFuture<>();
        SidTokenResolver resolver = new SidTokenResolver(configuration, Mono.defer(() -> {
            resolving.countDown();
            return Mono.fromFuture(idpAnswer);
        }));
        CaffeineOidcTokenCache cache = new CaffeineOidcTokenCache(resolver, configuration);

        CompletableFuture<AuthenticatedRequest> request = cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).toFuture();
        assertThat(resolving.await(10, TimeUnit.SECONDS)).isTrue();
        cache.invalidateBySid(SID).block();
        idpAnswer.complete(null);

        assertThatThrownBy(() -> request.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(OidcAuthenticationException.class);
        // Not cached: the next request resolves the token again, and is refused too
        assertThatThrownBy(() -> cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).block())
            .isInstanceOf(OidcAuthenticationException.class);
        assertThat(resolver.calls()).isEqualTo(2);
    }

    @Test
    void invalidateBySidShouldDropATokenReloadedBeforeItsEvictionWasNotified() {
        AtomicLong nanos = new AtomicLong();
        Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        SidTokenResolver resolver = new SidTokenResolver(configuration, Mono.empty());
        CaffeineOidcTokenCache cache = new CaffeineOidcTokenCache(resolver, configuration, nanos::get, tasks::add);

        CompletableFuture<AuthenticatedRequest> firstRequest = cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).toFuture();
        runAll(tasks);
        assertThat(firstRequest.join().user()).isEqualTo("bob@domain.tld");

        // The token expires, and a request reloads it before the removal listener of the expired entry runs:
        // the listener then unindexes the reloaded token.
        nanos.addAndGet(EXPIRATION.plusSeconds(1).toNanos());
        CompletableFuture<AuthenticatedRequest> reload = cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).toFuture();
        runAll(tasks);
        assertThat(reload.join().user()).isEqualTo("bob@domain.tld");
        assertThat(resolver.calls()).isEqualTo(2);

        cache.invalidateBySid(SID).block();
        CompletableFuture<AuthenticatedRequest> afterLogout = cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).toFuture();
        runAll(tasks);

        assertThat(afterLogout).isCompletedExceptionally();

        // Not cached any more: the next request resolves the token again, and is refused too
        CompletableFuture<AuthenticatedRequest> nextRequest = cache.resolve(ClientConfiguration.NO_PATH_PREFIX, TOKEN_1).toFuture();
        runAll(tasks);
        assertThat(nextRequest).isCompletedExceptionally();
        assertThat(resolver.calls()).isEqualTo(3);
    }

    private static void runAll(Queue<Runnable> tasks) {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    private static class SidTokenResolver extends OidcTokenResolver {
        private final Mono<Void> idpAnswer;
        private final AtomicInteger calls = new AtomicInteger();

        SidTokenResolver(WebAdminProxyConfiguration configuration, Mono<Void> idpAnswer) {
            super(null, null, configuration);
            this.idpAnswer = idpAnswer;
        }

        @Override
        public Mono<AuthenticatedRequest> resolve(String pathPrefix, String token) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                return idpAnswer.thenReturn(new AuthenticatedRequest("bob@domain.tld", "client", null, null, Optional.of(SID)));
            });
        }

        int calls() {
            return calls.get();
        }
    }

    private static class SidlessTokenResolver extends OidcTokenResolver {
        SidlessTokenResolver(WebAdminProxyConfiguration configuration) {
            super(null, null, configuration);
        }

        @Override
        public Mono<AuthenticatedRequest> resolve(String pathPrefix, String token) {
            return Mono.just(new AuthenticatedRequest("bob@domain.tld", "client", null, null, Optional.empty()));
        }
    }
}
