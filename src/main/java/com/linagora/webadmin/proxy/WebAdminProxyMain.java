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

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebAdminProxyMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebAdminProxyMain.class);
    private static final String WORKING_DIRECTORY_PROPERTY = "working.directory";

    public static void main(String[] args) throws IOException {
        String workingDir = System.getProperty(WORKING_DIRECTORY_PROPERTY);
        if (workingDir == null) {
            throw new IllegalArgumentException("Server requires a -D" + WORKING_DIRECTORY_PROPERTY + " property");
        }

        File configFile = new File(workingDir, "conf/configuration.json");
        WebAdminProxyConfiguration config = WebAdminProxyConfiguration.from(configFile);

        WebAdminProxyGuiceServer server = WebAdminProxyGuiceServer.forModule(new WebAdminProxyModule(config));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown signal received");
            server.stop();
        }));

        server.awaitStop();
    }
}
