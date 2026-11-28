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
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CaffeineOidcTokenCache implements OidcTokenCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineOidcTokenCache.class);
    private static final long LOGGED_OUT_SIDS_MAX_SIZE = 100_000;

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
    // Sessions logged out within the cache expiration. invalidateBySid evicts the tokens indexed at that time; a token
    // indexed later (its resolution was in flight, or the removal listener unindexed a reloaded token) is caught here.
    private final Cache<String, Boolean> loggedOutSids;
    private final AtomicBoolean missingSidReported = new AtomicBoolean(false);

    @Inject
    public CaffeineOidcTokenCache(OidcTokenResolver resolver, WebAdminProxyConfiguration configuration) {
        this(resolver, configuration, Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

    @VisibleForTesting
    CaffeineOidcTokenCache(OidcTokenResolver resolver, WebAdminProxyConfiguration configuration, Ticker ticker, Executor executor) {
        AsyncCacheLoader<Key, AuthenticatedRequest> loader = (key, loadExecutor) ->
            resolver.resolve(key.pathPrefix(), key.token())
                .doOnNext(auth -> {
                    auth.sessionId().ifPresentOrElse(
                        sid -> sidToTokens.put(sid, key),
                        () -> reportMissingSid(auth));
                    LOGGER.debug("Caching token info: user={}, clientId={}", auth.user(), auth.clientId());
                })
                .subscribeOn(Schedulers.fromExecutor(loadExecutor))
                .toFuture();

        cache = Caffeine.newBuilder()
            .ticker(ticker)
            .executor(executor)
            .expireAfterWrite(configuration.tokenCacheExpiration())
            .removalListener((Key key, AuthenticatedRequest auth, RemovalCause cause) -> {
                if (cause.wasEvicted() && auth != null) {
                    auth.sessionId().ifPresent(sid -> sidToTokens.remove(sid, key));
                }
            })
            .buildAsync(loader);

        loggedOutSids = Caffeine.newBuilder()
            .ticker(ticker)
            .executor(executor)
            .expireAfterWrite(configuration.tokenCacheExpiration())
            .maximumSize(LOGGED_OUT_SIDS_MAX_SIZE)
            .build();
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
        Key key = new Key(pathPrefix, bearerToken);
        return Mono.fromFuture(cache.get(key))
            .<AuthenticatedRequest>handle((auth, sink) -> {
                if (auth.sessionId().filter(sid -> loggedOutSids.getIfPresent(sid) != null).isPresent()) {
                    auth.sessionId().ifPresent(sid -> sidToTokens.remove(sid, key));
                    cache.synchronous().invalidate(key);
                    sink.error(new OidcAuthenticationException("The OIDC session of this token was logged out"));
                    return;
                }
                sink.next(auth);
            });
    }

    @Override
    public Mono<Void> invalidateBySid(String sid) {
        // The mark is written before the index is read, and a token is indexed before its mark is checked:
        // a token missed by this snapshot is refused by resolve.
        loggedOutSids.put(sid, true);
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
