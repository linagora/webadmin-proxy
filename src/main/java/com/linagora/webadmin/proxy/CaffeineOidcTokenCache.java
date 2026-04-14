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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CaffeineOidcTokenCache implements OidcTokenCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineOidcTokenCache.class);

    private final AsyncLoadingCache<String, AuthenticatedRequest> cache;

    @Inject
    public CaffeineOidcTokenCache(OidcTokenResolver resolver, OidcConfiguration configuration) {
        AsyncCacheLoader<String, AuthenticatedRequest> loader = (token, executor) ->
            resolver.resolve(token)
                .doOnNext(auth -> LOGGER.debug("Caching token info: user={}, clientId={}", auth.user(), auth.clientId()))
                .subscribeOn(Schedulers.fromExecutor(executor))
                .toFuture();

        cache = Caffeine.newBuilder()
            .expireAfterWrite(configuration.tokenCacheExpiration())
            .buildAsync(loader);
    }

    @Override
    public Mono<AuthenticatedRequest> resolve(String bearerToken) {
        return Mono.fromFuture(cache.get(bearerToken));
    }
}
