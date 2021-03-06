/*
 * CODENVY CONFIDENTIAL
 * __________________
 *
 *  [2012] - [2015] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.im.artifacts;

import com.codenvy.im.artifacts.helper.CDECArtifactHelper;
import com.codenvy.im.artifacts.helper.CDECMultiServerHelper;
import com.codenvy.im.artifacts.helper.CDECSingleServerHelper;
import com.codenvy.im.commands.Command;
import com.codenvy.im.managers.BackupConfig;
import com.codenvy.im.managers.Config;
import com.codenvy.im.managers.ConfigManager;
import com.codenvy.im.managers.InstallOptions;
import com.codenvy.im.managers.InstallType;
import com.codenvy.im.managers.UnknownInstallationTypeException;
import com.codenvy.im.utils.HttpTransport;
import com.codenvy.im.utils.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.core.rest.shared.dto.ApiInfo;

import javax.annotation.Nullable;
import javax.inject.Named;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.codenvy.im.utils.Commons.createDtoFromJson;
import static java.lang.String.format;

/**
 * @author Anatoliy Bazko
 * @author Dmytro Nochevnov
 */
@Singleton
public class CDECArtifact extends AbstractArtifact {
    private final Map<InstallType, CDECArtifactHelper> helpers = ImmutableMap.of(
            InstallType.SINGLE_SERVER, new CDECSingleServerHelper(this, configManager),
            InstallType.MULTI_SERVER, new CDECMultiServerHelper(this, configManager));

    public static final String NAME = "codenvy";

    @Inject
    public CDECArtifact(@Named("installation-manager.update_server_endpoint") String updateEndpoint,
                        HttpTransport transport,
                        ConfigManager configManager) {
        super(NAME, updateEndpoint, transport, configManager);
    }

    /** {@inheritDoc} */
    @Override
    public Version getInstalledVersion() throws IOException {
        try {
            if (configManager.detectInstallationType() == InstallType.SINGLE_SERVER) {
                // in single-node installation it's not required to modify '/etc/hosts' on the server where Codenvy is being installed
                return getInstalledVersion("localhost");
            } else {
                Config config = configManager.loadInstalledCodenvyConfig();
                return getInstalledVersion(config.getHostUrl());
            }
        } catch (UnknownInstallationTypeException | IOException e) {
            return null;
        }
    }

    @Nullable
    protected Version getInstalledVersion(String hostName) throws IOException {
        String response;
        try {
            String checkServiceUrl = format("http://%s/api/", hostName);
            response = transport.doOption(checkServiceUrl, null);
        } catch (IOException e) {
            return null;
        }

        ApiInfo apiInfo = createDtoFromJson(response, ApiInfo.class);
        if (apiInfo == null) {
            return null;
        }

        if (apiInfo.getIdeVersion() == null
            && apiInfo.getImplementationVersion() != null
            && apiInfo.getImplementationVersion().equals("0.26.0")) {
            return Version.valueOf("3.1.0"); // Old ide doesn't contain Ide Version property
        }

        return Version.valueOf(apiInfo.getIdeVersion());
    }

    /** {@inheritDoc} */
    @Override
    public int getPriority() {
        return 10;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getUpdateInfo(InstallType installType) throws IOException {
        if (installType != configManager.detectInstallationType()) {
            throw new IllegalArgumentException("Only update to the Codenvy of the same installation type is supported");
        }

        return ImmutableList.of("Unzip Codenvy binaries to /tmp/codenvy",
                                "Configure Codenvy",
                                "Patch resources before update",
                                "Move Codenvy binaries to /etc/puppet",
                                "Update Codenvy",
                                "Patch resources after update"
                               );
    }

    /** {@inheritDoc} */
    @Override
    public Command getUpdateCommand(Version versionToUpdate,
                                    Path pathToBinaries,
                                    InstallOptions installOptions) throws IOException, IllegalArgumentException {
        if (installOptions.getInstallType() != configManager.detectInstallationType()) {
            throw new IllegalArgumentException("Only update to the Codenvy of the same installation type is supported");
        }

        return getHelper(installOptions.getInstallType()).getUpdateCommand(versionToUpdate, pathToBinaries, installOptions);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getInstallInfo(InstallType installType) throws IOException {
        return getHelper(installType).getInstallInfo();
    }

    /** {@inheritDoc} */
    @Override
    public Command getInstallCommand(final Version versionToInstall,
                                     final Path pathToBinaries,
                                     final InstallOptions installOptions) throws IOException {

        return getHelper(installOptions.getInstallType())
                .getInstallCommand(versionToInstall, pathToBinaries, installOptions);
    }

    /** {@inheritDoc} */
    @Override
    public Command getBackupCommand(BackupConfig backupConfig) throws IOException {
        CDECArtifactHelper helper = getHelper(configManager.detectInstallationType());
        return helper.getBackupCommand(backupConfig);
    }

    /** {@inheritDoc} */
    @Override
    public Command getRestoreCommand(BackupConfig backupConfig) throws IOException {
        CDECArtifactHelper helper = getHelper(configManager.detectInstallationType());
        return helper.getRestoreCommand(backupConfig);
    }

    /** {@inheritDoc} */
    @Override
    public void updateConfig(Map<String, String> properties) throws IOException {
        Config config = configManager.loadInstalledCodenvyConfig();
        CDECArtifactHelper helper = getHelper(configManager.detectInstallationType());
        Command commands = helper.getUpdateConfigCommand(config, properties);
        commands.execute();
    }

    protected CDECArtifactHelper getHelper(InstallType type) {
        return helpers.get(type);
    }
}
