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

import org.apache.james.IsStartedProbe;
import org.apache.james.jwt.DefaultCheckTokenClient;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.dropwizard.DropWizardMetricFactory;
import org.apache.james.metrics.logger.DefaultMetricFactory;
import org.apache.james.modules.StartablesModule;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;

public class WebAdminProxyModule extends AbstractModule {

    private final WebAdminProxyConfiguration configuration;

    public WebAdminProxyModule(WebAdminProxyConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void configure() {
        install(new StartablesModule());
        Multibinder.newSetBinder(binder(), InitializationOperation.class);

        bind(WebAdminProxyConfiguration.class).toInstance(configuration);
        bind(OidcConfiguration.class).toInstance(configuration.oidcConfiguration());
        bind(DefaultCheckTokenClient.class).in(Scopes.SINGLETON);
        bind(OidcTokenResolver.class).in(Scopes.SINGLETON);
        bind(OidcTokenCache.class).to(CaffeineOidcTokenCache.class).in(Scopes.SINGLETON);
        bind(AllowedUrlsHandler.class).in(Scopes.SINGLETON);
        bind(ProxyInfoHandler.class).in(Scopes.SINGLETON);
        bind(WebAdminProxy.class).in(Scopes.SINGLETON);
        bind(IsStartedProbe.class).in(Scopes.SINGLETON);

        if (configuration.selfAdminEnabled()) {
            bind(MetricFactory.class).to(DropWizardMetricFactory.class);
            configuration.selfAdminPort().ifPresent(port -> install(new SelfAdminModule(port)));
        } else {
            bind(MetricFactory.class).to(DefaultMetricFactory.class).in(Scopes.SINGLETON);
        }
    }

    @Provides
    @Singleton
    public MetricRegistry provideMetricRegistry() {
        return new MetricRegistry();
    }

    @ProvidesIntoSet
    InitializationOperation webAdminProxyStart(WebAdminProxy instance) {
        return InitilizationOperationBuilder
            .forClass(WebAdminProxy.class)
            .init(instance::start);
    }

}
