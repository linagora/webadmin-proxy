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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

public class WebAdminProxyGuiceServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyGuiceServer.class);

    private final Module module;
    private WebAdminProxy proxy;

    public static WebAdminProxyGuiceServer forModule(Module module) {
        return new WebAdminProxyGuiceServer(module);
    }

    private WebAdminProxyGuiceServer(Module module) {
        this.module = module;
    }

    public void start() {
        LOGGER.info("Starting WebAdmin proxy server");
        Injector injector = Guice.createInjector(module);
        proxy = injector.getInstance(WebAdminProxy.class);
        proxy.start();
        LOGGER.info("WebAdmin proxy server started");
    }

    public void stop() {
        LOGGER.info("Stopping WebAdmin proxy server");
        if (proxy != null) {
            proxy.stop();
        }
        LOGGER.info("WebAdmin proxy server stopped");
    }

    public int getPort() {
        return proxy.getPort();
    }
}
