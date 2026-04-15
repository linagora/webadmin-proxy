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

import java.util.Optional;

import org.apache.james.IsStartedProbe;
import org.apache.james.utils.InitializationOperations;
import org.apache.james.webadmin.WebAdminServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;

public class WebAdminProxyGuiceServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyGuiceServer.class);

    private final Module module;
    private Injector injector;
    private WebAdminProxy proxy;

    public static WebAdminProxyGuiceServer forModule(Module module) {
        return new WebAdminProxyGuiceServer(module);
    }

    private WebAdminProxyGuiceServer(Module module) {
        this.module = module;
    }

    public void start() {
        LOGGER.info("Starting WebAdmin proxy server");
        injector = Guice.createInjector(module);
        proxy = injector.getInstance(WebAdminProxy.class);
        injector.getInstance(InitializationOperations.class).initModules();
        injector.getInstance(IsStartedProbe.class).notifyStarted();
        LOGGER.info("WebAdmin proxy server started");
    }

    public void stop() {
        LOGGER.info("Stopping WebAdmin proxy server");
        Optional.ofNullable(injector)
            .map(i -> i.getInstance(IsStartedProbe.class))
            .ifPresent(IsStartedProbe::notifyStoped);
        if (proxy != null) {
            proxy.stop();
        }
        Optional.ofNullable(injector)
            .map(i -> i.getExistingBinding(Key.get(WebAdminServer.class)))
            .map(binding -> binding.getProvider().get())
            .ifPresent(WebAdminServer::destroy);
        LOGGER.info("WebAdmin proxy server stopped");
    }

    public void awaitStop() {
        proxy.awaitStop();
    }

    public int getPort() {
        return proxy.getPort();
    }

    public int getAdminPort() {
        return injector.getInstance(WebAdminServer.class).getPort().getValue();
    }
}
