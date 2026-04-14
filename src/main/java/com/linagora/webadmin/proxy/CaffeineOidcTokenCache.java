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

import java.util.List;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CaffeineOidcTokenCache implements OidcTokenCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineOidcTokenCache.class);

    private final AsyncLoadingCache<String, AuthenticatedRequest> cache;
    private final SetMultimap<String, String> sidToTokens = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    @Inject
    public CaffeineOidcTokenCache(OidcTokenResolver resolver, OidcConfiguration configuration) {
        AsyncCacheLoader<String, AuthenticatedRequest> loader = (token, executor) ->
            resolver.resolve(token)
                .doOnNext(auth -> {
                    auth.sessionId().ifPresentOrElse(
                        sid -> sidToTokens.put(sid, token),
                        () -> LOGGER.warn("Token of user {} has no 'sid' claim — backchannel logout will not work for this session. Check OIDC configuration.", auth.user()));
                    LOGGER.debug("Caching token info: user={}, clientId={}", auth.user(), auth.clientId());
                })
                .subscribeOn(Schedulers.fromExecutor(executor))
                .toFuture();

        cache = Caffeine.newBuilder()
            .expireAfterWrite(configuration.tokenCacheExpiration())
            .removalListener((String token, AuthenticatedRequest auth, RemovalCause cause) -> {
                if (cause.wasEvicted() && auth != null) {
                    auth.sessionId().ifPresent(sid -> sidToTokens.remove(sid, token));
                }
            })
            .buildAsync(loader);
    }

    @Override
    public Mono<AuthenticatedRequest> resolve(String bearerToken) {
        return Mono.fromFuture(cache.get(bearerToken));
    }

    @Override
    public Mono<Void> invalidateBySid(String sid) {
        List<String> snapshot;
        synchronized (sidToTokens) {
            snapshot = List.copyOf(sidToTokens.get(sid));
        }
        return Mono.fromRunnable(() -> cache.synchronous().invalidateAll(snapshot))
            .subscribeOn(Schedulers.boundedElastic())
            .then(Mono.fromRunnable(() -> sidToTokens.removeAll(sid)))
            .then();
    }
}
