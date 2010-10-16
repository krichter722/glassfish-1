/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.admin.cli.embeddable;

import com.sun.enterprise.admin.cli.CLICommand;
import com.sun.enterprise.admin.cli.Environment;
import com.sun.enterprise.admin.cli.Parser;
import com.sun.enterprise.admin.cli.ProgramOptions;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandModel;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.common.util.admin.CommandModelImpl;
import org.glassfish.embeddable.CommandResult;
import org.glassfish.embeddable.GlassFishException;
import org.jvnet.hk2.annotations.ContractProvided;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author bhavanishankar@dev.java.net
 * @author sanjeeb.sahoo@sun.com
 */
@Service()
@Scoped(PerLookup.class) // this is a PerLookup service
@ContractProvided(org.glassfish.embeddable.CommandRunner.class)
// bcos CommandRunner interface can't depend on HK2, we need ContractProvided here.

public class CommandExecutorImpl implements org.glassfish.embeddable.CommandRunner {

    @Inject
    CommandRunner commandRunner;

    @Inject
    Habitat habitat;

    private boolean terse;

    private Logger logger = Logger.getAnonymousLogger();

    public CommandResult run(String command, String... args) throws GlassFishException {
        try {
            ActionReport actionReport = executeCommand(command, args);
            return convert(actionReport);
        } catch (CommandException e) {
            throw new GlassFishException(e);
        }
    }

    private ParameterMap getParameters(String command, String[] args) throws CommandException {
//        // I don't think we should be adding the ProgramOptions here. This should be done
//        // upfront based on information supplied by user during bootstrap process. No?
//        ProgramOptions po = new ProgramOptions(new Environment());
//        if (habitat.getComponent(ProgramOptions.class) == null) {
//            habitat.addComponent("program-options", po);
//        }
//
//        CLICommand cliCommand = CLICommand.getCommand(habitat, command);
//        CommandModelImpl commandModel = new CommandModelImpl(cliCommand.getClass());
        CommandModel commandModel = commandRunner.getModel(command, logger);
        Parser parser = new Parser(args, 0, commandModel.getParameters(), false);
        ParameterMap options = parser.getOptions();
        List<String> operands = parser.getOperands();
        System.out.println("options = " + options);
        System.out.println("operands = " + operands);
        options.set("DEFAULT", operands);
        // if command has a "terse" option, set it in options
        if (commandModel.getModelFor("terse") != null)
            options.set("terse", Boolean.toString(terse));
        return options;
    }

    public void setTerse(boolean terse) {
        this.terse = terse;
    }

    /* package */ ActionReport executeCommand(String command, String... args) throws CommandException {
        ParameterMap commandParams = getParameters(command, args);
        final ActionReport actionReport = createActionReport();

        org.glassfish.api.admin.CommandRunner.CommandInvocation inv =
                commandRunner.getCommandInvocation(command, actionReport);

        inv.parameters(commandParams).execute();

        return actionReport;
    }

    private CommandResult convert(final ActionReport actionReport) {
        return new CommandResult(){
            public ExitStatus getExitStatus() {
                final ActionReport.ExitCode actionExitCode = actionReport.getActionExitCode();
                switch (actionExitCode) {
                    case SUCCESS:
                        return ExitStatus.SUCCESS;
                    case WARNING:
                        return ExitStatus.WARNING;
                    case FAILURE:
                        return ExitStatus.FAILURE;
                    default:
                        throw new RuntimeException("Unknown exit code: " + actionExitCode);
                }
            }

            public String getOutput() {
                final ByteArrayOutputStream os = new ByteArrayOutputStream();
                try {
                    actionReport.writeReport(os);
                    return os.toString();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    try {
                        os.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }

            public Throwable getFailureCause() {
                return actionReport.getFailureCause();
            }
        };
    }

    private ActionReport createActionReport() {
        return habitat.getComponent(ActionReport.class, "plain");
    }

}
