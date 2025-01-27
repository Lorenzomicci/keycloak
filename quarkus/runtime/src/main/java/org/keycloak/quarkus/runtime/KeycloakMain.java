/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.quarkus.runtime;

import static org.keycloak.quarkus.runtime.Environment.getKeycloakModeFromProfile;
import static org.keycloak.quarkus.runtime.Environment.isDevProfile;
import static org.keycloak.quarkus.runtime.Environment.getProfileOrDefault;
import static org.keycloak.quarkus.runtime.Environment.isImportExportMode;
import static org.keycloak.quarkus.runtime.Environment.isTestLaunchMode;
import static org.keycloak.quarkus.runtime.cli.Picocli.parseAndRun;
import static org.keycloak.quarkus.runtime.cli.command.AbstractStartCommand.OPTIMIZED_BUILD_OPTION_LONG;
import static org.keycloak.quarkus.runtime.cli.command.Start.isDevProfileNotAllowed;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import picocli.CommandLine.ExitCode;

import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.Quarkus;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.quarkus.runtime.cli.ExecutionExceptionHandler;
import org.keycloak.quarkus.runtime.cli.PropertyException;
import org.keycloak.quarkus.runtime.cli.Picocli;
import org.keycloak.common.Version;
import org.keycloak.quarkus.runtime.cli.command.Start;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.managers.ApplianceBootstrap;
import org.keycloak.services.resources.KeycloakApplication;
import org.keycloak.utils.EmailValidationUtil;
import org.keycloak.utils.StringUtil;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * <p>The main entry point, responsible for initialize and run the CLI as well as start the server.
 */
@QuarkusMain(name = "keycloak")
@ApplicationScoped
public class KeycloakMain implements QuarkusApplication {

    private static final Logger log = Logger.getLogger(KeycloakMain.class);
    private static final String KEYCLOAK_ADMIN_ENV_VAR = "KEYCLOAK_ADMIN";
    private static final String KEYCLOAK_ADMIN_PASSWORD_ENV_VAR = "KEYCLOAK_ADMIN_PASSWORD";
    private static final String KEYCLOAK_ADMIN_FIRSTNAME_ENV_VAR = "KEYCLOAK_ADMIN_FIRSTNAME";
    private static final String KEYCLOAK_ADMIN_LASTNAME_ENV_VAR = "KEYCLOAK_ADMIN_LASTNAME";
    private static final String KEYCLOAK_ADMIN_EMAIL_ENV_VAR = "KEYCLOAK_ADMIN_EMAIL";
    private static final String KEYCLOAK_ADMIN_DEFAULT_EMAIL_DOMAIN = "keycloak.test";

    public static void main(String[] args) {
        System.setProperty("kc.version", Version.VERSION);
        List<String> cliArgs = null;
        try {
            cliArgs = Picocli.parseArgs(args);
        } catch (PropertyException e) {
            ExecutionExceptionHandler errorHandler = new ExecutionExceptionHandler();
            PrintWriter errStream = new PrintWriter(System.err, true);
            errorHandler.error(errStream, e.getMessage(), null);
            System.exit(ExitCode.USAGE);
            return;
        }

        if (cliArgs.isEmpty()) {
            cliArgs = new ArrayList<>(cliArgs);
            // default to show help message
            cliArgs.add("-h");
        } else if (isFastStart(cliArgs)) {
            // fast path for starting the server without bootstrapping CLI
            ExecutionExceptionHandler errorHandler = new ExecutionExceptionHandler();
            PrintWriter errStream = new PrintWriter(System.err, true);

            if (isDevProfileNotAllowed()) {
                errorHandler.error(errStream, Messages.devProfileNotAllowedError(Start.NAME), null);
                System.exit(ExitCode.USAGE);
                return;
            }

            try {
                Picocli.validateConfig(cliArgs, new Start(), new PrintWriter(System.out, true));
            } catch (PropertyException e) {
                errorHandler.error(errStream, e.getMessage(), null);
                System.exit(ExitCode.USAGE);
                return;
            }

            start(errorHandler, errStream, args);

            return;
        }

        // parse arguments and execute any of the configured commands
        parseAndRun(cliArgs);
    }

    private static boolean isFastStart(List<String> cliArgs) {
        // 'start --optimized' should start the server without parsing CLI
        return cliArgs.size() == 2 && cliArgs.get(0).equals(Start.NAME) && cliArgs.stream().anyMatch(OPTIMIZED_BUILD_OPTION_LONG::equals);
    }

    public static void start(ExecutionExceptionHandler errorHandler, PrintWriter errStream, String[] args) {
        try {
            Quarkus.run(KeycloakMain.class, (exitCode, cause) -> {
                if (cause != null) {
                    errorHandler.error(errStream,
                            String.format("Failed to start server in (%s) mode", getKeycloakModeFromProfile(getProfileOrDefault("prod"))),
                            cause.getCause());
                }

                if (Environment.isDistribution()) {
                    // assume that it is running the distribution
                    // as we are replacing the default exit handler, we need to force exit
                    System.exit(exitCode);
                }
            }, args);
        } catch (Throwable cause) {
            errorHandler.error(errStream,
                    String.format("Unexpected error when starting the server in (%s) mode", getKeycloakModeFromProfile(getProfileOrDefault("prod"))),
                    cause.getCause());
            System.exit(1);
        }
    }

    /**
     * Should be called after the server is fully initialized
     */
    @Override
    public int run(String... args) throws Exception {
        if (!isImportExportMode()) {
            createAdminUser();
        }

        if (isDevProfile()) {
            Logger.getLogger(KeycloakMain.class).warnf("Running the server in development mode. DO NOT use this configuration in production.");
        }

        int exitCode = ApplicationLifecycleManager.getExitCode();

        if (isTestLaunchMode() || isImportExportMode()) {
            // in test mode we exit immediately
            // we should be managing this behavior more dynamically depending on the tests requirements (short/long lived)
            Quarkus.asyncExit(exitCode);
        } else {
            Quarkus.waitForExit();
        }

        return exitCode;
    }

    private void createAdminUser() {
        String adminUserName = System.getenv(KEYCLOAK_ADMIN_ENV_VAR);
        String adminPassword = System.getenv(KEYCLOAK_ADMIN_PASSWORD_ENV_VAR);
        String tmpFirstName = System.getenv(KEYCLOAK_ADMIN_FIRSTNAME_ENV_VAR);
        String tmpLastName = System.getenv(KEYCLOAK_ADMIN_LASTNAME_ENV_VAR);
        String tmpEmail = System.getenv(KEYCLOAK_ADMIN_EMAIL_ENV_VAR);

        if (StringUtil.isBlank(adminUserName) || StringUtil.isBlank(adminPassword)) {
            return;
        }

        // try to create admin user only with username and password
        if (StringUtil.isBlank(tmpFirstName)) {
            tmpFirstName = adminUserName;
        }

        if (StringUtil.isBlank(tmpLastName)) {
            tmpLastName = adminUserName;
        }

        if (StringUtil.isBlank(tmpEmail)) {
            tmpEmail = adminUserName + "@" + KEYCLOAK_ADMIN_DEFAULT_EMAIL_DOMAIN;
        }

        if (!EmailValidationUtil.isValidEmail(tmpEmail)) {
            log.errorf("The admin user %s is not created because the associated email is invalid: %s. "
                    + "Please set a valid email in the KEYCLOAK_ADMIN_EMAIL environment variable.", adminUserName, tmpEmail);
            return;
        }

        final String adminFirstName = tmpFirstName;
        final String adminLastName = tmpLastName;
        final String adminEmail = tmpEmail;

        KeycloakSessionFactory sessionFactory = KeycloakApplication.getSessionFactory();

        try {
            KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
                new ApplianceBootstrap(session).createMasterRealmUser(adminUserName,
                        adminPassword, adminFirstName, adminLastName, adminEmail);
            });
        } catch (Throwable t) {
            ServicesLogger.LOGGER.addUserFailed(t, adminUserName, Config.getAdminRealm());
        }
    }
}
