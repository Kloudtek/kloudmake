/*
 * Copyright (c) 2015. Kelewan Technologies Ltd
 */

package com.kloudtek.kloudmake.resource.core;

import com.kloudtek.kloudmake.annotation.Attr;
import com.kloudtek.kloudmake.annotation.Execute;
import com.kloudtek.kloudmake.annotation.Inject;
import com.kloudtek.kloudmake.annotation.KMResource;
import com.kloudtek.kloudmake.exception.KMRuntimeException;
import com.kloudtek.kloudmake.host.Host;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This resource is used to install a package.
 */
@KMResource
public class PackageResource {
    private static final Logger logger = LoggerFactory.getLogger(PackageResource.class);
    @NotEmpty()
    @Attr
    private String name;
    @Attr
    private boolean installed;
    @Attr
    private String version;
    @Attr
    private Provider provider;
    @Attr
    private PackageProvider pkgProvider;
    @Attr
    private boolean includeRecommended;
    @Inject
    private Host host;

    @Execute
    public void execute() throws KMRuntimeException {
        pkgProvider = new AptPackageProvider(host);
        pkgProvider.update();
        String installed = pkgProvider.checkCurrentlyInstalled(name);
        logger.debug("Installed version for package {} is {}", name, installed);
        if (version == null) {
            version = pkgProvider.checkLatestAvailable(name);
            logger.debug("Available version for package {} is {}", name, version);
        }
        if (pkgProvider.isNewer(version, installed)) {
            logger.info("installing package {} - {}", name, version);
            pkgProvider.install(name, version, includeRecommended);
        } else {
            logger.debug("Package {} is already same version or newer than {}", name, version);
        }
    }

    public enum Provider {
        APT
    }
}
