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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * The same token presented under two path prefixes is resolved against each prefix's provider and entries.
     */
    private record Key(String pathPrefix, String token) {
        @Override
        public String toString() {
            return "Key[pathPrefix=" + pathPrefix + "]";
        }
    }

    private final AsyncLoadingCache<Key, AuthenticatedRequest> cache;
    private final SetMultimap<String, Key> sidToTokens = Multimaps.synchronizedSetMultimap(HashMultimap.create());
    private final AtomicBoolean missingSidReported = new AtomicBoolean(false);

    @Inject
    public CaffeineOidcTokenCache(OidcTokenResolver resolver, WebAdminProxyConfiguration configuration) {
        AsyncCacheLoader<Key, AuthenticatedRequest> loader = (key, executor) ->
            resolver.resolve(key.pathPrefix(), key.token())
                .doOnNext(auth -> {
                    auth.sessionId().ifPresentOrElse(
                        sid -> sidToTokens.put(sid, key),
                        () -> reportMissingSid(auth));
                    LOGGER.debug("Caching token info: user={}, clientId={}", auth.user(), auth.clientId());
                })
                .subscribeOn(Schedulers.fromExecutor(executor))
                .toFuture();

        cache = Caffeine.newBuilder()
            .expireAfterWrite(configuration.tokenCacheExpiration())
            .removalListener((Key key, AuthenticatedRequest auth, RemovalCause cause) -> {
                if (cause.wasEvicted() && auth != null) {
                    auth.sessionId().ifPresent(sid -> sidToTokens.remove(sid, key));
                }
            })
            .buildAsync(loader);
    }

    private void reportMissingSid(AuthenticatedRequest auth) {
        if (missingSidReported.compareAndSet(false, true)) {
            LOGGER.warn("OIDC tokens have no 'sid' claim — backchannel logout will not work. Check OIDC configuration.");
        } else {
            LOGGER.debug("Token of user {} has no 'sid' claim", auth.user());
        }
    }

    @Override
    public Mono<AuthenticatedRequest> resolve(String pathPrefix, String bearerToken) {
        return Mono.fromFuture(cache.get(new Key(pathPrefix, bearerToken)));
    }

    @Override
    public Mono<Void> invalidateBySid(String sid) {
        List<Key> snapshot;
        synchronized (sidToTokens) {
            snapshot = List.copyOf(sidToTokens.get(sid));
        }
        return Mono.fromRunnable(() -> cache.synchronous().invalidateAll(snapshot))
            .subscribeOn(Schedulers.boundedElastic())
            .then(Mono.fromRunnable(() -> sidToTokens.removeAll(sid)))
            .then();
    }
}
