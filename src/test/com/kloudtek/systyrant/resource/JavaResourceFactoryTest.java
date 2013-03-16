/*
 * Copyright (c) 2013 KloudTek Ltd
 */

package com.kloudtek.systyrant.resource;

import com.kloudtek.systyrant.AbstractContextTest;
import com.kloudtek.systyrant.STContext;
import com.kloudtek.systyrant.annotation.*;
import com.kloudtek.systyrant.exception.FieldInjectionException;
import com.kloudtek.systyrant.exception.InvalidAttributeException;
import com.kloudtek.systyrant.exception.InvalidResourceDefinitionException;
import com.kloudtek.systyrant.exception.ResourceCreationException;
import com.kloudtek.systyrant.service.filestore.FileStore;
import com.kloudtek.systyrant.host.Host;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class JavaResourceFactoryTest extends AbstractContextTest {
    @Test
    public void testInjectContext() throws Throwable {
        registerAndCreate(InjectContext.class, "injectctx").execute();
    }

    public static class InjectContext {
        @Inject
        private STContext context;

        @Prepare
        public void test() {
            assertEquals(context, STContext.get());
        }
    }

    @Test
    public void testInjectResource() throws Throwable {
        register(InjectResource.class);
        Resource resource = create(InjectResource.class);
        execute();
        assertTrue(resource == resource.getJavaImpl(InjectResource.class).resourceCopy);
    }

    public static class InjectResource {
        private Resource resourceCopy;
        @Inject
        private Resource resource;

        @Prepare
        public void test() {
            resourceCopy = resource;
        }
    }

    @Test
    public void testAction() throws Throwable {
        registerAndCreate(ActionSingleStages.class).execute();
        ActionSingleStages javaAction = findJavaAction(ActionSingleStages.class);
        assertTrue(javaAction.prepared);
        assertTrue(javaAction.executed);
        assertTrue(javaAction.cleaned);
    }

    public static class ActionSingleStages {
        private boolean prepared;
        private boolean executed;
        private boolean cleaned;

        @Prepare
        public void prepare() {
            prepared = true;
        }

        @Execute
        public void exec() {
            executed = true;
        }

        @Cleanup
        public void clean() {
            cleaned = true;
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testActionMultipleStages() throws Throwable {
        registerAndCreate(ActionMultipleStages.class).execute();
        ActionMultipleStages javaAction = findJavaAction(ActionMultipleStages.class);
        assertEquals(2, javaAction.count);
    }

    public static class ActionMultipleStages {
        private int count;

        @Prepare
        @Execute
        public void test() {
            count++;
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testInjectServiceByClass() throws Throwable {
        registerAndCreate(InjectServiceByClass.class).execute();
    }

    public static class InjectServiceByClass {
        @Service
        private FileStore fsservice;
        @Service
        private Host hostservice;

        @Execute
        public void test() {
            assertNotNull(hostservice);
            assertNotNull(fsservice);
            assertTrue(hostservice instanceof Host);
            assertTrue(fsservice instanceof FileStore);
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testInjectServiceByName() throws Throwable {
        registerAndCreate(InjectServiceByFieldName.class).execute();
    }

    public static class InjectServiceByFieldName {
        @Service
        private FileStore filestore;
        @Service
        private Host host;

        @Execute
        public void test() {
            assertNotNull(host);
            assertNotNull(filestore);
            assertTrue(host instanceof Host);
            assertTrue(filestore instanceof FileStore);
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testInjectServiceByAnnoName() throws Throwable {
        registerAndCreate(InjectServiceByAnnoName.class).execute();
    }

    public static class InjectServiceByAnnoName {
        @Service("filestore")
        private FileStore filestoreserv;
        @Service("host")
        private Host hostserv;

        @Execute
        public void test() {
            assertNotNull(hostserv);
            assertNotNull(filestoreserv);
            assertTrue(hostserv instanceof Host);
            assertTrue(filestoreserv instanceof FileStore);
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testInjectServiceWithInject() throws Throwable {
        registerAndCreate(InjectServiceWithInjectAnno.class).execute();
    }

    public static class InjectServiceWithInjectAnno {
        @Inject
        private FileStore filestoreserv;
        @Inject
        private Host hostserv;

        @Execute
        public void test() {
            assertNotNull(hostserv);
            assertNotNull(filestoreserv);
            assertTrue(hostserv instanceof Host);
            assertTrue(filestoreserv instanceof FileStore);
        }
    }

    @Test(dependsOnMethods = "testAction", expectedExceptions = FieldInjectionException.class)
    public void testInjectServiceByInvalidName() throws Throwable {
        registerAndCreate(InjectServiceByInvalidName.class).execute();
    }

    public static class InjectServiceByInvalidName {
        @Service
        private Object filestoreWhoKnows;

        @Execute
        public void test() {
        }
    }

    @Test
    public void testInjectChildResources() throws Throwable {
        register(InjectChildResources.class);
        Resource r1 = createTestResource();
        Resource r2 = create(InjectChildResources.class);
        Resource r3 = createTestResource();
        r3.setParent(r2);
        Resource r4 = createTestResource();
        r4.setParent(r2);
        createTestResource();
        InjectChildResources impl = findJavaAction(InjectChildResources.class);
        assertContainsSame(impl.childrensPersist,r3,r4);
    }

    public static class InjectChildResources {
        @Resources("childof")
        private List<Resource> childrens;
        private List<Resource> childrensPersist;

        @Execute
        public void test() {
            childrensPersist = childrens;
        }
    }

    @Test
    public void testVerifySyncOrder() throws Throwable {
        register(VerifySyncOrder.class);
        Resource res = create(VerifySyncOrder.class);
        VerifySyncOrder vs = res.getJavaImpl(VerifySyncOrder.class);
        execute();
        assertEquals(vs.verifyGlobal,0);
        assertEquals(vs.syncGlobal,1);
        assertEquals(vs.verifySpecific,2);
        assertEquals(vs.syncSpecific,3);
    }

    public static class VerifySyncOrder {
        private int count;
        private int verifyGlobal = -1;
        private int verifySpecific = -1;
        private int syncGlobal = -1;
        private int syncSpecific = -1;

        @Verify
        public boolean verifyGlobal() {
            verifyGlobal = count++;
            return false;
        }

        @Verify(value = "spec")
        public boolean veritySpecific() {
            verifySpecific = count++;
            return false;
        }

        @Sync
        public void syncGlobal() {
            syncGlobal = count++;
        }

        @Sync(value = "spec",order = -1)
        public void syncSpecific() {
            syncSpecific = count++;
        }
    }

    @Test(dependsOnMethods = "testAction")
    public void testInjectAttr() throws Throwable {
        Class<InjectAttrWithAttrAnnotation> cl = InjectAttrWithAttrAnnotation.class;
        register(cl);
        Resource resource = create(cl);
        resource.set("test1","value1");
        resource.set("test2","value2");
        resource.set("test3","55");
        resource.set("test4","value4");
        execute();
        assertEquals(resource.getJavaImpl(cl).attrs,new Object[]{"value1","value2",55,"value4"});
    }

    public static class InjectAttrWithAttrAnnotation {
        public Object[] attrs;
        @Attr
        public String test1;
        @Attr("test2")
        public String test2x;
        @Attr
        public Integer test3;
        @Inject
        public String test4;

        @Execute
        public void copy() {
            attrs = new Object[] {test1,test2x,test3,test4};
        }
    }

    @Test(dependsOnMethods = "testInjectAttr")
    public void testUpdateInjectedAttr() throws Throwable {
        Class<UpdateInjectedAttr> cl = UpdateInjectedAttr.class;
        register(cl);
        Resource resource = create(cl);
        resource.set("test1","value1");
        resource.set("test2","value2");
        resource.set("test3","55");
        execute();
        assertEquals(resource.get("test1"),"x1");
        assertEquals(resource.get("test2"),"x2");
        assertEquals(resource.get("test3"),"100");
        assertEquals(resource.get("test4"),"x4");
    }

    public static class UpdateInjectedAttr {
        public Object[] attrs;
        @Attr
        public String test1;
        @Attr("test2")
        public String test2x;
        @Attr
        public Integer test3;
        @Inject
        public String test4;

        @Execute
        public void copy() {
            test1 = "x1";
            test2x = "x2";
            test3 = 100;
            test4 = "x4";
        }
    }
}