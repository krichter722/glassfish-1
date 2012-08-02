/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2006-2012 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.internal.data;

import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.deployment.*;
import org.glassfish.api.container.Sniffer;
import org.glassfish.api.container.Container;
import org.glassfish.api.event.*;
import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.EventListener.Event;
import org.glassfish.internal.deployment.DeploymentTracing;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.glassfish.internal.deployment.Deployment;
import org.jvnet.hk2.config.TransactionFailure;
import org.glassfish.hk2.api.PreDestroy;

import java.util.*;
import java.util.logging.Logger;
import java.beans.PropertyVetoException;

import com.sun.logging.LogDomains;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.Module;
import com.sun.enterprise.config.serverbeans.Engine;

/**
 * Information about a running application. Applications are composed of modules.
 * Modules run in an individual container.
 *
 * @author Jerome Dochez
 */
public class ApplicationInfo extends ModuleInfo {

    final private Collection<ModuleInfo> modules = new ArrayList<ModuleInfo>();

    // The reversed modules contain the same elements as modules but just in 
    // reversed order, they are used when stopping/unloading the application.
    // The modules should be stopped/unloaded in the reverse order of what 
    // they were originally loaded/started.
    final private LinkedList<ModuleInfo> reversedModules = new LinkedList<ModuleInfo>();

    final private ReadableArchive source;
    final private Map<String, Object> transientAppMetaData = new HashMap<String, Object>();

    private String libraries;
    private boolean isJavaEEApp = false;
    private ClassLoader appClassLoader;
    private boolean isLoaded = false;


    /**
     * Creates a new instance of an ApplicationInfo
     *
     * @param events
     * @param source the archive for this application
     * @param name name of the application
     */
    public ApplicationInfo(final Events events, ReadableArchive source,
                           String name) {
        super(events, name, new LinkedHashSet<EngineRef>(), null);
        this.source = source;
    }

    public void add(EngineRef ref) {
        engines.add(ref);
    }
    
    public void addTransientAppMetaData(String metaDataKey, 
        Object metaDataValue) {
        if (metaDataValue != null) {
            transientAppMetaData.put(metaDataKey, metaDataValue);
        }
    }

    public <T> T getTransientAppMetaData(String key, Class<T> metadataType) {
        Object metaDataValue = transientAppMetaData.get(key);
        if (metaDataValue != null) {
            return metadataType.cast(metaDataValue);
        }
        return null;
    }

    /**
     * Returns the registration name for this application
     * @return the application registration name
     */
    public String getName() {
        return name;
    }  
    
    /**
     * Returns the deployment time libraries for this application
     * @return the libraries
     */
    public String getLibraries() {
        return libraries;
    }

    /**
     * Sets the deployment time libraries for this application
     * @param libraries the libraries
     */
    public void setLibraries(String libraries) {
        this.libraries = libraries;
    }

    /**
     * Sets the application classloader for this application
     * @param cLoader application classloader
     */
    public void setAppClassLoader(ClassLoader cLoader) {
        appClassLoader = cLoader;
    }

    /**
     * Returns whether this application is a JavaEE application
     * @return the isJavaEEApp flag
     */
    public boolean isJavaEEApp() {
        return isJavaEEApp;
    }

    /**
     * Sets whether this application is a JavaEE application
     * @param engineInfos the engine info list
     */
    public void setIsJavaEEApp(List<EngineInfo> engineInfos) {
        for (EngineInfo engineInfo : engineInfos) {
            String moduleType = engineInfo.getSniffer().getModuleType(); 
            if (moduleType.equals("web") || moduleType.equals("ejb") || 
                moduleType.equals("appclient") || 
                moduleType.equals("connector") ||  moduleType.equals("ear")) {
                isJavaEEApp = true;
                break;
            }
        }
    }


    /**
     * Returns the directory where the application bits are located
     * @return the application bits directory
     * */
    public ReadableArchive getSource() {
        return source;
    }


    /**
     * Returns the modules of this application
     * @return the modules of this application
     */
    public Collection<ModuleInfo> getModuleInfos() {
        return modules;
    }

    /**
     * Returns the list of sniffers that participated in loaded this
     * application
     *
     * @return array of sniffer that loaded the application's module
     */
    public Collection<Sniffer> getSniffers() {
        List<Sniffer> sniffers = new ArrayList<Sniffer>();
        for (EngineRef ref : engines) {
            sniffers.add(ref.getContainerInfo().getSniffer());
        }

        for (ModuleInfo module : modules) {
            sniffers.addAll(module.getSniffers());
        }
        return sniffers;
    }

    /*
     * Returns the EngineRef for a particular container type
     * @param type the container type
     * @return the module info is this application as a module implemented with
     * the passed container type
     */
    public <T extends Container> Collection<EngineRef> getEngineRefsForContainer(Class<T> type) {
        Set<EngineRef> refs = new LinkedHashSet<EngineRef>();
        for (ModuleInfo info : modules) {
            EngineRef ref = null;
            try {
                ref = info.getEngineRefForContainer(type);
            } catch (Exception e) {
                // ignore, wrong container
            }
            if (ref!=null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    protected ExtendedDeploymentContext getSubContext(ModuleInfo info, ExtendedDeploymentContext context) {
        return context;
    }

    public void load(ExtendedDeploymentContext context, ProgressTracker tracker)
            throws Exception {
        Logger logger = context.getLogger();
        if (isLoaded) {
            logger.fine("Application is already loaded.");
            return;
        }

        context.setPhase(ExtendedDeploymentContext.Phase.LOAD);
        DeploymentTracing tracing = context.getModuleMetaData(DeploymentTracing.class);
        if (tracing!=null) {
            tracing.addMark(
                DeploymentTracing.Mark.LOAD);
        }


        super.load(context, tracker);

        appClassLoader = context.getClassLoader();

        for (ModuleInfo module : modules) {
            if (tracing!=null) {
                tracing.addModuleMark(DeploymentTracing.ModuleMark.LOAD, module.getName());
            }
            module.load(getSubContext(module,context), tracker);
            if (tracing!=null) {
                tracing.addModuleMark(DeploymentTracing.ModuleMark.LOADED, module.getName());
            }
        }

        isLoaded = true;

        if (tracing!=null) {
            tracing.addMark(DeploymentTracing.Mark.LOAD_EVENTS);
        }

        if (events!=null) {
            events.send(new Event<ApplicationInfo>(Deployment.APPLICATION_LOADED, this), false);
        }
        if (tracing!=null) {
            tracing.addMark(DeploymentTracing.Mark.LOADED);
        }
        
    }


    public void start(
        ExtendedDeploymentContext context,
        ProgressTracker tracker) throws Exception {

        DeploymentTracing tracing = context.getModuleMetaData(DeploymentTracing.class);
        if (tracing!=null) {
            tracing.addMark(DeploymentTracing.Mark.START);
        }
        
        super.start(context, tracker);
        // registers all deployed items.
        for (ModuleInfo module : getModuleInfos()) {
            if (tracing!=null) {
                tracing.addModuleMark(DeploymentTracing.ModuleMark.START, module.getName());
            }
            module.start(getSubContext(module, context), tracker);
            if (tracing!=null) {
                tracing.addModuleMark(
                    DeploymentTracing.ModuleMark.STARTED, module.getName());
            }

        }
        if (tracing!=null) {
            tracing.addMark(DeploymentTracing.Mark.START_EVENTS);
        }

        if (events!=null) {
            events.send(new Event<ApplicationInfo>(Deployment.APPLICATION_STARTED, this), false);
        }
        if (tracing!=null) {
            tracing.addMark(DeploymentTracing.Mark.STARTED);
        }

    }

    public void stop(ExtendedDeploymentContext context, Logger logger) {

        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(appClassLoader);
            context.setClassLoader(appClassLoader);
            super.stop(context, logger);
            for (ModuleInfo module : reversedModules) {
                module.stop(getSubContext(module, context), logger);
            }
            if (events!=null) {
                events.send(new Event<ApplicationInfo>(Deployment.APPLICATION_STOPPED, this), false);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
    }

    public void unload(ExtendedDeploymentContext context) {

        Logger logger = context.getLogger();
        if (!isLoaded) {
            logger.fine("Application is already unloaded.");
            return;
        }

        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(appClassLoader);
            context.setClassLoader(appClassLoader);
            super.unload(context);
            for (ModuleInfo module : reversedModules) {
                module.unload(getSubContext(module, context));
            }
            isLoaded = false;
            if (events!=null) {
                events.send(new Event<ApplicationInfo>(Deployment.APPLICATION_UNLOADED, this), false);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
            context.setClassLoader(null);
        }
    }

    public boolean suspend(Logger logger) {

        boolean isSuccess = super.suspend(logger);

        for (ModuleInfo module : reversedModules) {
            if (!module.suspend(logger)) {
                isSuccess = false;
            }
        }
        return isSuccess;
    }

    public boolean resume(Logger logger) {

        boolean isSuccess = super.resume(logger);

        for (ModuleInfo module : modules) {
            if (!module.resume(logger)) {
                isSuccess=false;
            }
        }

        return isSuccess;
    }

    public void clean(ExtendedDeploymentContext context) throws Exception {
        super.clean(context);
        for (ModuleInfo info : reversedModules) {
            info.clean(getSubContext(info,context));
            info = null;
        }

        // clean up the app level classloader
        if (appClassLoader != null) {
            try {
                PreDestroy.class.cast(appClassLoader).preDestroy();
            } catch (Exception e) {
                // ignore, the class loader does not need to be
                // explicitely stopped or already stopped
            }
            appClassLoader = null;
        }

        // clean the module class loaders if they are not already 
        // been cleaned
        for (ModuleInfo module : getModuleInfos()) {
            if (module.getClassLoaders() != null) {
                for (ClassLoader cloader : module.getClassLoaders()) {
                    try {
                        PreDestroy.class.cast(cloader).preDestroy();
                    } catch (Exception e) {
                        // ignore, the class loader does not need to be 
                        // explicitely stopped or already stopped
                    }
                }
                module.cleanClassLoaders();
            }
        }

        if (events!=null) {
            events.send(new EventListener.Event<DeploymentContext>(Deployment.APPLICATION_CLEANED, context), false);
        }
    }
    

    /**
     * Saves its state to the configuration. this method must be called within a transaction
     * to the configured Application instance.
     *
     * @param app the application being persisted
     */
    public void save(Application app) throws TransactionFailure, PropertyVetoException {

        for (EngineRef ref : engines) {
            Engine engine = app.createChild(Engine.class);
            app.getEngine().add(engine);
            ref.save(engine);
        }

        for (ModuleInfo module : modules) {
            Module modConfig = app.getModule(module.getName());
            if (modConfig == null) {
                // not a JavaEE module, create it here
                modConfig = app.createChild(Module.class);
                modConfig.setName(module.getName());
                app.getModule().add(modConfig);
            }
            module.save(modConfig);
        }        
    }

    public void addModule(ModuleInfo info) {
        modules.add(info);
        reversedModules.addFirst(info);
    }

    public boolean isLoaded() {
        return isLoaded;
    }

}
