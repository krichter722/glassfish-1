/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
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
package com.sun.enterprise.admin.cli.cluster;

import com.sun.enterprise.universal.process.Jps;
import com.sun.enterprise.universal.process.ProcessUtils;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.io.FileUtils;
import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jvnet.hk2.annotations.*;
import org.jvnet.hk2.component.*;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;

import com.sun.enterprise.admin.cli.*;
import com.sun.enterprise.admin.cli.remote.RemoteCommand;
import com.sun.enterprise.util.ObjectAnalyzer;

/**
 * Delete a local server instance.
 * Wipeout the node dir if it is the last instance under the node
 *
 * Performance Note:  getServerDirs().getServerDir() is all inlined by the JVM
 * because the class instance is immutable.
 *
 * @author Byron Nevins
 */
@Service(name = "delete-local-instance")
@Scoped(PerLookup.class)
public class DeleteLocalInstanceCommand extends LocalInstanceCommand {

    @Param(name = "instance_name", primary = true, optional = true)
    private String instanceName0;

    /** initInstance goes to great lengths to figure out the correct directory structure.
     * We don't care about such errors.  If the dir is not there -- then this is a
     * simple error about trying to delete an instance that doesn't exist...
     * Thank goodness for overriding methods!!
     * @throws CommandException
     */
    @Override
    protected void initInstance() throws CommandException {
        try {
            tempDump("before initInstance");
            super.initInstance();
            tempDump("after initInstance");
        }
        catch (CommandException e) {
            throw e;
        }
        catch (Exception e) {
            throw new CommandException(Strings.get("DeleteInstance.noInstance"));
        }
    }

    /**
     * We most definitely do not want to create directories for nodes here!!
     * @param f the directory to create
     */
    protected boolean mkdirs(File f) {
        return false;
    }

    @Override
    protected void validate()
            throws CommandException, CommandValidationException {
        instanceName = instanceName0;
        super.validate();
        tempDump("Dump of ServerDirs:", ObjectAnalyzer.toString(getServerDirs()));
        if (!StringUtils.ok(getServerDirs().getServerName()))
            throw new CommandException(Strings.get("DeleteInstance.noInstanceName"));

        File dasProperties = getServerDirs().getDasPropertiesFile();

        if (dasProperties.isFile()) {
            setDasDefaults(dasProperties);
        }

        tempDump("Is the instance's directory really a directory?: " + getServerDirs().getServerDir().isDirectory());
        if (!getServerDirs().getServerDir().isDirectory())
            throw new CommandException(Strings.get("DeleteInstance.noWhack",
                    getServerDirs().getServerDir()));
    }

    /**
     */
    @Override
    protected int executeCommand()
            throws CommandException, CommandValidationException {

        tempDump("in execute()");
        tempDump(ObjectAnalyzer.toStringWithSuper(this));

        if (isRunning()) {
            tempDump("*****  ERROR!!!! instance is running !!!!");
            int prevpid = getPrevPid();
            tempDump("prevpid = " + prevpid);
            tempDump("ProcessUtils.isProcessRunning(" + prevpid + ") -- returned: " +  ProcessUtils.isProcessRunning(getPrevPid()));
            Map<String, Integer> procs = Jps.getProcessTable();
            Set<Map.Entry<String,Integer>> set = procs.entrySet();
            Iterator<Map.Entry<String,Integer>> it = set.iterator();
            while(it.hasNext()) {
                Map.Entry<String,Integer> entry = it.next();
                tempDump("FROM JPS -- name=" + entry.getKey() + ", pid= " + entry.getValue());
            }
            
            throw new CommandException(Strings.get("DeleteInstance.running"));
        }

        tempDump("instance was not running -- calling _unregister_instance now");
        doRemote();

        tempDump("_unreg worked -- whacking filesystem now...");
        whackFilesystem();
        tempDump("everything worked -- returning success...");
        return SUCCESS;
    }

    /**
     * Ask DAS to wipe it out from domain.xml
     */
    private void doRemote() throws CommandException {
        try {
            RemoteCommand rc = new RemoteCommand("_unregister-instance", programOpts, env);
            rc.execute("_unregister-instance",
                    "--node", getServerDirs().getServerParentDir().getName(),
                    //"--remote_only", "true",
                    getServerDirs().getServerName());
        }
        catch (CommandException ce) {
            // Let's add our $0.02 to this Exception!

            tempDump("Got an exception unregistering instance: ", ce.toString());


            Throwable t = ce.getCause();
            String newString = Strings.get("DeleteInstance.remoteError",
                    ce.getLocalizedMessage());

            if (t != null)
                throw new CommandException(newString, t);
            else
                throw new CommandException(newString);
        }
    }
}
