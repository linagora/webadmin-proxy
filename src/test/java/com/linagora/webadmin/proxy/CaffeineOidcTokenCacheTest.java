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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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

    private final Logger logger = (Logger) LoggerFactory.getLogger(CaffeineOidcTokenCache.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;
    private CaffeineOidcTokenCache testee;

    @BeforeEach
    void setUp() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        WebAdminProxyConfiguration configuration = WebAdminProxyConfiguration.builder()
            .oidcConfiguration(new OidcConfiguration(null, null, List.of(), "email", Duration.ofMinutes(5)))
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
