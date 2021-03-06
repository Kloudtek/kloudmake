/*
 * Copyright (c) 2015. Kelewan Technologies Ltd
 */

package com.kloudtek.kloudmake.resource;

import com.kloudtek.kloudmake.AbstractVagrantTest;
import com.kloudtek.kloudmake.exception.InvalidResourceDefinitionException;
import com.kloudtek.kloudmake.exception.KMRuntimeException;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.script.ScriptException;
import java.io.IOException;

public class FileResourceVagrantTest extends AbstractVagrantTest {
    @BeforeClass(groups = "vagrant")
    public void init() throws InvalidResourceDefinitionException, IOException, KMRuntimeException {
        super.init();
    }

    @Test(groups = "vagrant")
    public void testCreateNonExistingFile() throws KMRuntimeException, IOException, ScriptException {
        sshHost.exec("rm -rf /root/testCreateNonExistingFile");
        ctx.getResourceManager().createResource("core.file").set("path", "/root/testCreateNonExistingFile")
                .set("content", "hello").set("owner", "uucp").set("group", "fuse").set("permissions", "rwxr-xrw-");
        execute();
        String stats = sshHost.exec("stat -c '%F:%s:%A:%U:%G' /root/testCreateNonExistingFile");
        Assert.assertEquals(stats, "regular file:5:-rwxr-xrw-:uucp:fuse\n");
    }
}
