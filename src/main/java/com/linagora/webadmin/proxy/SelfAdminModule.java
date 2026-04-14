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
import java.util.Set;

import jakarta.inject.Named;

import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.logger.DefaultMetricFactory;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.webadmin.FixedPortSupplier;
import org.apache.james.webadmin.PortSupplier;
import org.apache.james.webadmin.RandomPortSupplier;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.WebAdminConfiguration;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.authentication.AuthenticationFilter;
import org.apache.james.webadmin.authentication.NoAuthenticationFilter;
import org.apache.james.webadmin.mdc.LoggingRequestFilter;
import org.apache.james.webadmin.mdc.RequestLogger;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;

public class SelfAdminModule extends AbstractModule {

    private final int port;

    public SelfAdminModule(int port) {
        this.port = port;
    }

    @Override
    protected void configure() {
        bind(WebAdminServer.class).in(Scopes.SINGLETON);
        bind(BackchannelLogoutRoutes.class).in(Scopes.SINGLETON);
        Multibinder.newSetBinder(binder(), Routes.class).addBinding().to(BackchannelLogoutRoutes.class);
        Multibinder.newSetBinder(binder(), RequestLogger.class);
    }

    @Provides
    @Singleton
    @Named("webAdminRoutes")
    public List<Routes> provideRoutes(Set<Routes> routes) {
        return ImmutableList.copyOf(routes);
    }

    @Provides
    @Singleton
    public WebAdminConfiguration provideWebAdminConfiguration() {
        PortSupplier portSupplier = port == 0 ? new RandomPortSupplier() : new FixedPortSupplier(port);
        return WebAdminConfiguration.builder()
            .enabled()
            .corsDisabled()
            .host("127.0.0.1")
            .port(portSupplier)
            .build();
    }

    @Provides
    @Singleton
    public AuthenticationFilter provideAuthenticationFilter() {
        return new NoAuthenticationFilter();
    }

    @Provides
    @Singleton
    public MetricFactory provideMetricFactory() {
        return new DefaultMetricFactory();
    }

    @Provides
    @Singleton
    public LoggingRequestFilter provideLoggingRequestFilter(Set<RequestLogger> requestLoggers) {
        return new LoggingRequestFilter(requestLoggers);
    }

    @ProvidesIntoSet
    public InitializationOperation webAdminServerStart(WebAdminServer instance) {
        return InitilizationOperationBuilder
            .forClass(WebAdminServer.class)
            .init(instance::start);
    }
}
