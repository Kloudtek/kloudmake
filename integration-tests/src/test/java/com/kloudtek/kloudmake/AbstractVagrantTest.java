/*
 * Copyright (c) 2015. Kelewan Technologies Ltd
 */

package com.kloudtek.kloudmake;

import com.kloudtek.kloudmake.exception.*;
import com.kloudtek.kloudmake.host.Host;
import com.kloudtek.kloudmake.host.LocalHost;
import com.kloudtek.kloudmake.host.SshHost;
import com.kloudtek.kloudmake.resource.vagrant.SharedFolder;
import com.kloudtek.kloudmake.resource.vagrant.VagrantResource;
import com.kloudtek.util.FileUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.testng.Assert;

import java.io.File;
import java.io.IOException;

public class AbstractVagrantTest {
    public static final String TEST = "test.test";
    public static final String UNIQUETEST = "test.uniquetest";
    public static final String VAGRANTDIR = "_vagrant";
    public static final String TESTDIR = VAGRANTDIR + File.separator + "_vagrant";
    protected KMContextImpl ctx;
    protected ResourceManager resourceManager;
    protected Host host;
    protected SshHost sshHost;

    public void init() throws KMRuntimeException, InvalidResourceDefinitionException, InjectException, IOException {
        ctx = new KMContextImpl();
        host = ctx.getHost();
        Executor exec = new DefaultExecutor();
        File vagrantDir = new File(VAGRANTDIR);
        if (!vagrantDir.exists()) {
            if (!vagrantDir.mkdirs()) {
                throw new IOException("Unable to create " + vagrantDir.getPath());
            }
        }
        File vagrantfile = new File(vagrantDir, "Vagrantfile");
        if (!vagrantfile.exists()) {
            FileUtils.toString(vagrantfile, "Vagrant::Config.run do |config|\n  config.vm.box = \"ubuntu-precise64\"\nend\n");
        }
        exec.setWorkingDirectory(vagrantDir);
        exec.setStreamHandler(new PumpStreamHandler(System.out));
        exec.execute(CommandLine.parse("vagrant up"));
        resourceManager = ctx.getResourceManager();
//        resourceManager.registerJavaResource(TestResource.class, TEST);
//        resourceManager.registerJavaResource(UniqueTestResource.class, UNIQUETEST);
        Resource vagrant = resourceManager.createResource("vagrant:vagrant");
        vagrant.set("dir", VAGRANTDIR);
        vagrant.set("box", "ubuntu-precise64");
        SharedFolder testFolder = new SharedFolder(true, true, "test", TESTDIR, "/test");
        Resource testDirRes = resourceManager.createResource(testFolder);
        ctx.setDefaultParent(vagrant);
        sshHost = VagrantResource.initHost(sshHost, new LocalHost(), VAGRANTDIR);
        ctx.inject(sshHost);
//        try {
//            Field field = AbstractHost.class.getDeclaredField("hostProviderManager");
//            field.setAccessible(true);
//            field.set(sshHost, ctx.getProvidersManagementService().getProviderManager(HostProviderManager.class));
//        } catch (NoSuchFieldException | IllegalAccessException e) {
//            throw new STRuntimeException(e.getMessage(), e);
//        }
        sshHost.start();
    }

    public Resource createTestResource() throws ResourceCreationException {
        return resourceManager.createResource(TEST);
    }

    public Resource createTestResource(Resource dependency) throws ResourceCreationException {
        Resource testResource = createTestResource();
        testResource.addDependency(dependency);
        return testResource;
    }

    public Resource createTestResource(String id, Resource dependency) throws ResourceCreationException, InvalidAttributeException {
        Resource testResource = createTestResource(id);
        testResource.addDependency(dependency);
        return testResource;
    }

    public Resource createChildTestResource(String id, Resource parent) throws ResourceCreationException, InvalidAttributeException {
        return resourceManager.createResource(TEST, id, parent);
    }

    public Resource createTestResource(String id) throws ResourceCreationException, InvalidAttributeException {
        return createTestElement("id", id);
    }

    public Resource createTestElement(String attr, String val) throws ResourceCreationException, InvalidAttributeException {
        return createTestResource().set(attr, val);
    }

    public void execute() throws KMRuntimeException {
        execute(true);
    }

    public void execute(boolean expected) throws KMRuntimeException {
        Assert.assertEquals(ctx.execute(), expected);
    }
}
