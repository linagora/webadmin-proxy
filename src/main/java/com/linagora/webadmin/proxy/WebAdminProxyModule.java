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

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

public class WebAdminProxyModule extends AbstractModule {

    private final WebAdminProxyConfiguration configuration;

    public WebAdminProxyModule(WebAdminProxyConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void configure() {
        bind(WebAdminProxyConfiguration.class).toInstance(configuration);
        bind(WebAdminProxy.class).in(Scopes.SINGLETON);
    }
}
