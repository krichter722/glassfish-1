/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.server;


import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.single.StaticModulesRegistry;
import com.sun.enterprise.util.Result;
import org.glassfish.api.FutureProvider;
import org.glassfish.api.Startup;
import org.glassfish.api.StartupRunLevel;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.EventTypes;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.hk2.runlevel.RunLevelController;
import org.glassfish.hk2.utilities.AbstractActiveDescriptor;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.internal.api.Init;
import org.glassfish.internal.api.InitRunLevel;
import org.glassfish.internal.api.PostStartup;
import org.glassfish.internal.api.PostStartupRunLevel;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.testing.junit.HK2Runner;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * AppServerStartup tests.
 *
 * @author Tom Beerbower
 */
public class AppServerStartupTest extends HK2Runner {

    // ----- data members ----------------------------------------------------

    /**
     * The AppServerStartup instance to test.
     */
    private AppServerStartup as;

    /**
     * The test results.
     */
    private static Results results;


    /**
     * Map of exceptions to be thrown from the postConstruct.
     */
    private static Map<Class, RuntimeException> mapPostConstructExceptions = null;

    /**
     * List of {@link Future}s returned from {@link FutureProvider#getFutures()} by the {@link Startup}
     * services during progression to the start up run level.
     */
    private static List<TestFuture> listFutures = null;


    // ----- test initialization ---------------------------------------------

    /**
     * Reset the results prior to each test.
     */
    @Before
    public void beforeTest() {
        before();
        
        as = testLocator.getService(AppServerStartup.class);
        Assert.assertNotNull(as);
        
        mapPostConstructExceptions = new HashMap<Class, RuntimeException>();
        listFutures                = new LinkedList<TestFuture>();
        results                    = new Results(as.runLevelController);

        as.events.register(results);
    }

    /**
     * Ensure that things are stopped after the test... if not then call stop.
     */
    @After
    public void afterTest() {
        if (as != null) {
            if (as.runLevelController.getCurrentRunLevel() > 0) {
                // force a stop to ensure that the services are released
                as.env.setStatus(ServerEnvironment.Status.started);
                as.stop();
            }

            as.events.unregister(results);
        }
        results                    = null;
        listFutures                = null;
        mapPostConstructExceptions = null;
    }


    // ----- tests -----------------------------------------------------------

    /**
     * Call the {@link AppServerStartup#run} method and make sure that
     * the run level services are constructed and destroyed at the proper
     * run levels.
     */
    @Test
    public void testRunLevelServices() {

        // create the list of Futures returned from TestStartupService
        listFutures.add(new TestFuture());
        listFutures.add(new TestFuture());
        listFutures.add(new TestFuture());

        testRunAppServerStartup();

        Assert.assertTrue(as.env.getStatus() == ServerEnvironment.Status.started);

        Assert.assertEquals(2, results.getListEvents().size());
        Assert.assertEquals(EventTypes.SERVER_STARTUP, results.getListEvents().get(0));
        Assert.assertEquals(EventTypes.SERVER_READY, results.getListEvents().get(1));

        // assert that the run level services have been constructed
        Assert.assertTrue(results.isConstructed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestInitRunLevelService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupRunLevelService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestPostStartupService.class, PostStartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestPostStartupRunLevelService.class, PostStartupRunLevel.VAL));

        as.stop();

        Assert.assertFalse(as.env.getStatus() == ServerEnvironment.Status.started);

        Assert.assertEquals(4, results.getListEvents().size());
        Assert.assertEquals(EventTypes.PREPARE_SHUTDOWN, results.getListEvents().get(2));
        Assert.assertEquals(EventTypes.SERVER_SHUTDOWN, results.getListEvents().get(3));

        // assert that the run level services have been destroyed
        Assert.assertTrue(results.isDestroyed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isDestroyed(TestInitRunLevelService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isDestroyed(TestStartupService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isDestroyed(TestStartupRunLevelService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isDestroyed(TestPostStartupService.class, PostStartupRunLevel.VAL));
        Assert.assertTrue(results.isDestroyed(TestPostStartupRunLevelService.class, PostStartupRunLevel.VAL));
    }

    /**
     * Test the {@link AppServerStartup#run} method with an exception thrown from an init
     * service that should cause a failure during init.  Make sure that the init run level
     * services are constructed at the proper run levels.
     */
    @Test
    public void testRunLevelServicesWithInitException() {
        testRunLevelServicesWithException(TestInitService.class);

        // make sure that the server has not been started
        Assert.assertFalse(as.env.getStatus() == ServerEnvironment.Status.started);

        // assert that the run level services have been constructed
        Assert.assertTrue(results.isConstructed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestInitRunLevelService.class, InitRunLevel.VAL));
        // assert that startup & post-startup services are not constructed since the failure occurs during init
        Assert.assertFalse(results.isConstructed(TestStartupService.class));
        Assert.assertFalse(results.isConstructed(TestStartupRunLevelService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupRunLevelService.class));
    }

    /**
     * Test the {@link AppServerStartup#run} method with an exception thrown from a startup
     * service that should cause a failure during startup.  Make sure that the init and
     * startup run level services are constructed at the proper run levels.
     */
    @Test
    public void testRunLevelServicesWithStartupException() {
        testRunLevelServicesWithException(TestStartupService.class);

        // make sure that the server has not been started
        Assert.assertFalse(as.env.getStatus() == ServerEnvironment.Status.started);

        Assert.assertTrue(results.getListEvents().contains(EventTypes.SERVER_SHUTDOWN));

        // assert that the run level services have been constructed
        Assert.assertTrue(results.isConstructed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestInitRunLevelService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupRunLevelService.class, StartupRunLevel.VAL));
        // assert that the post-startup service is not constructed since shutdown occurs during startup
        Assert.assertFalse(results.isConstructed(TestPostStartupService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupRunLevelService.class));
    }

    /**
     * Test the {@link AppServerStartup#run} method with an exception thrown from a
     * post-startup service that should cause a failure during post-startup.  Make sure
     * that the run level services are constructed at the proper run levels.
     */
    @Test
    public void testRunLevelServicesWithPostStartupException() {
        testRunLevelServicesWithException(TestPostStartupService.class);

        // assert that the run level services have been constructed
        Assert.assertTrue(results.isConstructed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestInitRunLevelService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupRunLevelService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestPostStartupService.class, PostStartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestPostStartupRunLevelService.class, PostStartupRunLevel.VAL));
    }

    /**
     * Test the {@link AppServerStartup#run} method with an exception thrown from a
     * {@link Future} should cause a failed result during startup.  Make sure that the init
     * and startup run level services are constructed at the proper run levels.  Also ensure
     * that the failed {@link Future} causes a shutdown.
     */
    @Test
    public void testRunLevelServicesWithFuturesException() {

        // create the list of Futures returned from TestStartupService
        listFutures.add(new TestFuture());
        listFutures.add(new TestFuture(new Exception("Exception from Future.")));
        listFutures.add(new TestFuture());

        testRunAppServerStartup();

        // make sure that the server has not been started
        Assert.assertFalse(as.env.getStatus() == ServerEnvironment.Status.started);

        Assert.assertTrue(results.getListEvents().contains(EventTypes.SERVER_SHUTDOWN));

        // assert that the run level services have been constructed
        Assert.assertTrue(results.isConstructed(TestInitService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestInitRunLevelService.class, InitRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupService.class, StartupRunLevel.VAL));
        Assert.assertTrue(results.isConstructed(TestStartupRunLevelService.class, StartupRunLevel.VAL));
        // assert that the post-startup service is not constructed since shutdown occurs during startup
        Assert.assertFalse(results.isConstructed(TestPostStartupService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupRunLevelService.class));
    }


    // ----- helper methods --------------------------------------------------

    /**
     * Helper method to run the app server after asserting that the results are clean.
     */
    private void testRunAppServerStartup() {
        // assert that we have clean results to start
        Assert.assertFalse(results.isConstructed(TestInitService.class));
        Assert.assertFalse(results.isConstructed(TestInitRunLevelService.class));
        Assert.assertFalse(results.isConstructed(TestStartupService.class));
        Assert.assertFalse(results.isConstructed(TestStartupRunLevelService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupService.class));
        Assert.assertFalse(results.isConstructed(TestPostStartupRunLevelService.class));

        as.run();
    }

    /**
     * Helper method to call {@link AppServerStartup#run()}.  Sets up an exception
     * to be thrown from {@link PostConstruct#postConstruct()} of the given class.
     *
     * @param badServiceClass  the service class that the exception will be thrown from
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    private void testRunLevelServicesWithException(Class badServiceClass) {
        // set an exception to be thrown from TestStartupService.postConstruct()
        mapPostConstructExceptions.put(badServiceClass,
                new RuntimeException("Exception from " + badServiceClass.getSimpleName() + ".postConstruct"));

        // create the list of Futures returned from TestStartupService
        listFutures.add(new TestFuture());

        testRunAppServerStartup();
    }


    // ----- Results inner class ---------------------------------------------

    /**
     * Test results
     */
    private static class Results implements EventListener {
        /**
         * Map of constructed run level services to run levels.
         */
        private Map<Class, Integer> mapConstructedLevels = new HashMap<Class, Integer>();

        /**
         * Map of destroyed run level services to run levels.
         */
        private Map<Class, Integer> mapDestroyedLevels = new HashMap<Class, Integer>();

        /**
         * List of server events.
         */
        private List<EventTypes> listEvents = new LinkedList<EventTypes>();

        /**
         * The run level service.
         */
        private RunLevelController rls;

        public Results(RunLevelController rls) {
            this.rls = rls;
        }

        public void recordConstruction(Class cl) {
            mapConstructedLevels.put(cl, rls.getPlannedRunLevel());
        }

        public void recordDestruction(Class cl) {
            mapDestroyedLevels.put(cl, rls.getCurrentRunLevel());
        }

        public boolean isConstructed(Class cl) {
            return mapConstructedLevels.keySet().contains(cl);
        }

        public boolean isConstructed(Class cl, Integer runLevel) {
            Integer recLevel = mapConstructedLevels.get(cl);
            return recLevel != null && recLevel.equals(runLevel);
        }

        public boolean isDestroyed(Class cl) {
            return mapDestroyedLevels.keySet().contains(cl);
        }

        public boolean isDestroyed(Class cl, Integer runLevel) {
            Integer recLevel = mapDestroyedLevels.get(cl);
            return recLevel != null && recLevel.equals(runLevel);
        }

        public List<EventTypes> getListEvents() {
            return listEvents;
        }

        @Override
        public void event(Event event) {
            listEvents.add(event.type());
        }
    }


    // ----- test services inner classes -------------------------------------

    /**
     * Abstract service that will update the test results from
     * {@link PostConstruct#postConstruct()}.
     */
    public static abstract class TestService implements PostConstruct, PreDestroy {
        @Override
        public void postConstruct() {
            AppServerStartupTest.results.recordConstruction(this.getClass());
            if (mapPostConstructExceptions != null) {
                RuntimeException ex = mapPostConstructExceptions.get(getClass());
                if (ex != null) {
                    throw ex;
                }
            }
        }

        @Override
        public void preDestroy() {
            AppServerStartupTest.results.recordDestruction(this.getClass());
        }
    }

    /**
     * Init service that implements the old style {@link Init} interface.
     */
    @Service
    public static class TestInitService extends TestService implements Init {
    }

    /**
     * Init service annotated with the new style {@link InitRunLevel} annotation.
     */
    @Service
    @RunLevel(InitRunLevel.VAL)
    public static class TestInitRunLevelService extends TestService  {
    }

    /**
     * Startup service that implements the old style {@link Startup} interface.
     */
    @Service
    public static class TestStartupService extends TestService implements Startup, FutureProvider {
        @Override
        public Lifecycle getLifecycle() {
            return Lifecycle.START;
        }

        @Override
        public List getFutures() {
            return listFutures;
        }
    }

    /**
     * Startup service annotated with the new style {@link StartupRunLevel} annotation.
     */
    @Service
    @RunLevel(StartupRunLevel.VAL)
    public static class TestStartupRunLevelService extends TestService {
    }

    /**
     * Post-startup service that implements the old style {@link PostStartup} interface.
     */
    @Service
    public static class TestPostStartupService extends TestService implements PostStartup {
    }

    /**
     * Post-startup service annotated with the new style {@link PostStartupRunLevel} annotation.
     */
    @Service
    @RunLevel(PostStartupRunLevel.VAL)
    public static class TestPostStartupRunLevelService extends TestService {
    }

    // ----- TestFuture inner classes ----------------------------------------

    /**
     * Future implementation used for test {@link Startup} implementations that
     * also implement {@link FutureProvider}.
     */
    public static class TestFuture implements Future<Result<Thread>> {

        private boolean   canceled        = false;
        private boolean   done            = false;
        private Exception resultException = null;

        public TestFuture() {
        }

        public TestFuture(Exception resultException) {
            this.resultException = resultException;
        }

        @Override
        public boolean cancel(boolean b) {
            if (done) {
                return false;
            }
            canceled = done = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return canceled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Result<Thread> get() throws InterruptedException, ExecutionException {

            Result<Thread> result = resultException == null ?
                            new Result<Thread>(Thread.currentThread()) :
                            new Result<Thread>(resultException);
            done = true;

            return result;
        }

        @Override
        public Result<Thread> get(long l, TimeUnit timeUnit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return get();
        }
    }
}
