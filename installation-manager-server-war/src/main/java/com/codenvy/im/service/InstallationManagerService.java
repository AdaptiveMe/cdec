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
package com.codenvy.im.service;

import com.codenvy.im.artifacts.Artifact;
import com.codenvy.im.artifacts.ArtifactFactory;
import com.codenvy.im.artifacts.ArtifactNotFoundException;
import com.codenvy.im.artifacts.ArtifactProperties;
import com.codenvy.im.artifacts.CDECArtifact;
import com.codenvy.im.facade.IMArtifactLabeledFacade;
import com.codenvy.im.managers.AdditionalNodesConfigUtil;
import com.codenvy.im.managers.BackupConfig;
import com.codenvy.im.managers.Config;
import com.codenvy.im.managers.ConfigManager;
import com.codenvy.im.managers.DownloadAlreadyStartedException;
import com.codenvy.im.managers.DownloadNotStartedException;
import com.codenvy.im.managers.InstallOptions;
import com.codenvy.im.managers.InstallType;
import com.codenvy.im.managers.NodeConfig;
import com.codenvy.im.managers.PropertyNotFoundException;
import com.codenvy.im.response.ArtifactInfo;
import com.codenvy.im.response.BackupInfo;
import com.codenvy.im.response.DownloadProgressResponse;
import com.codenvy.im.response.InstallArtifactInfo;
import com.codenvy.im.response.NodeInfo;
import com.codenvy.im.response.Response;
import com.codenvy.im.response.UpdatesArtifactInfo;
import com.codenvy.im.saas.SaasAccountServiceProxy;
import com.codenvy.im.saas.SaasUserCredentials;
import com.codenvy.im.utils.HttpException;
import com.codenvy.im.utils.IllegalVersionException;
import com.codenvy.im.utils.Version;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;

import org.eclipse.che.api.account.shared.dto.AccountReference;
import org.eclipse.che.api.account.shared.dto.SubscriptionDescriptor;
import org.eclipse.che.api.auth.AuthenticationException;
import org.eclipse.che.api.auth.server.dto.DtoServerImpls;
import org.eclipse.che.api.auth.shared.dto.Credentials;
import org.eclipse.che.api.auth.shared.dto.Token;
import org.eclipse.che.api.core.ApiException;
import org.eclipse.che.dto.server.JsonStringMapImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codenvy.im.utils.Commons.createArtifactOrNull;
import static com.codenvy.im.utils.Commons.createVersionOrNull;
import static com.codenvy.im.utils.Commons.toJson;

/**
 * @author Dmytro Nochevnov
 *         We deny concurrent access to the services by marking this class as Singleton
 *         so as there are operations which couldn't execute simulteniously: addNode, removeNode, backup, restore, updateCodenvyProperty
 */
@Singleton
@Path("/")
@RolesAllowed({"system/admin"})
@Api(value = "/im", description = "Installation manager")
public class InstallationManagerService {

    private static final Logger LOG = LoggerFactory.getLogger(InstallationManagerService.class);
    private static final String DOWNLOAD_TOKEN    = UUID.randomUUID().toString();

    private static final String    SECURE_VALUE_MASK    = "*****";
    private static final Pattern[] PRIVATE_KEY_PATTERNS = new Pattern[]{Pattern.compile("password$"),
                                                                        Pattern.compile("_pass$"),
                                                                        Pattern.compile("secret$")};

    protected final IMArtifactLabeledFacade delegate;
    protected final ConfigManager           configManager;
    protected final String                  backupDir;

    protected SaasUserCredentials saasUserCredentials;

    @Inject
    public InstallationManagerService(@Named("installation-manager.backup_dir") String backupDir,
                                      IMArtifactLabeledFacade delegate,
                                      ConfigManager configManager) {
        this.delegate = delegate;
        this.configManager = configManager;
        this.backupDir = backupDir;
    }

    /**
     * Starts downloading artifacts.
     */
    @POST
    @Path("downloads")
    @ApiOperation(value = "Starts downloading artifacts", response = DownloadToken.class)
    @ApiResponses(value = {@ApiResponse(code = 202, message = "OK"),
                           @ApiResponse(code = 400, message = "Illegal version format or artifact name"),
                           @ApiResponse(code = 409, message = "Downloading already in progress"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response startDownload(
        @QueryParam(value = "artifact") @ApiParam(value = "Artifact name", allowableValues = CDECArtifact.NAME) String artifactName,
        @QueryParam(value = "version") @ApiParam(value = "Version number") String versionNumber) {

        try {
            // DownloadManager has to support tokens
            DownloadToken downloadToken = new DownloadToken();
            downloadToken.setId(DOWNLOAD_TOKEN);

            Artifact artifact = createArtifactOrNull(artifactName);
            Version version = createVersionOrNull(versionNumber);

            delegate.startDownload(artifact, version);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.ACCEPTED).entity(downloadToken).build();
        } catch (ArtifactNotFoundException | IllegalVersionException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.BAD_REQUEST);
        } catch (DownloadAlreadyStartedException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.CONFLICT);
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Interrupts downloading.
     */
    @DELETE
    @Path("downloads/{id}")
    @ApiOperation(value = "Interrupts downloading")
    @ApiResponses(value = {@ApiResponse(code = 204, message = "OK"),
                           @ApiResponse(code = 404, message = "Downloading not found"),
                           @ApiResponse(code = 409, message = "Downloading not in progress"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response stopDownload(@PathParam("id") @ApiParam(value = "Download Id") String downloadId) {
        try {
            delegate.stopDownload();
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.NO_CONTENT).build();
        } catch (DownloadNotStartedException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.CONFLICT);
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Gets download progress.
     */
    @GET
    @Path("downloads/{id}")
    @ApiOperation(value = "Gets download progress", response = DownloadProgressResponse.class)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK"),
                           @ApiResponse(code = 404, message = "Downloading not found"),
                           @ApiResponse(code = 409, message = "Downloading not in progress"),
                           @ApiResponse(code = 500, message = "Server error")})
    @Produces(MediaType.APPLICATION_JSON)
    public javax.ws.rs.core.Response getDownloadProgress(@PathParam("id") @ApiParam(value = "Download Id") String downloadId) {
        try {
            DownloadProgressResponse downloadProgress = delegate.getDownloadProgress();
            return javax.ws.rs.core.Response.ok(downloadProgress).build();
        } catch (DownloadNotStartedException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.CONFLICT);
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Deletes downloaded artifact.
     */
    @DELETE
    @Path("downloads/{artifact}/{version}")
    @ApiOperation(value = "Deletes downloaded artifact")
    @ApiResponses(value = {@ApiResponse(code = 204, message = "Successfully removed"),
                           @ApiResponse(code = 400, message = "Illegal version format or artifact name"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response deleteDownloadedArtifact(
            @PathParam("artifact") @ApiParam(value = "Artifact name") final String artifactName,
            @PathParam("version") @ApiParam(value = "Artifact version") final String artifactVersion) {

        try {
            Artifact artifact = createArtifact(artifactName);
            Version version = Version.valueOf(artifactVersion);

            delegate.deleteDownloadedArtifact(artifact, version);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.NO_CONTENT).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Get the list of actual updates from Update Server.
     */
    @GET
    @Path("updates")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets the list of actual updates from Update Server", response = UpdatesArtifactInfo.class, responseContainer = "List")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response getUpdates() {
        try {
            Collection<UpdatesArtifactInfo> updates = delegate.getAllUpdates(ArtifactFactory.createArtifact(CDECArtifact.NAME));
            return javax.ws.rs.core.Response.ok(updates).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Get download ID being in progress.
     */
    @GET
    @Path("downloads")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get download ID being in progress")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Ok"),
                           @ApiResponse(code = 409, message = "Downloading not in progress")})
    public javax.ws.rs.core.Response getDownloads() {
        try {
            String id = delegate.getDownloadIdInProgress();
            Map<String, String> ids = ImmutableMap.of("id", id);

            return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(ids)).build();
        } catch (DownloadNotStartedException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.CONFLICT);
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Gets the list of downloaded and installed artifacts.
     */
    @GET
    @Path("artifacts")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets the list of downloaded and installed artifacts", response = ArtifactInfo.class, responseContainer = "List")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response getArtifacts() {
        try {
            Collection<ArtifactInfo> artifacts = delegate.getArtifacts();
            return javax.ws.rs.core.Response.ok(artifacts).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Gets installed artifacts.
     */
    @GET
    @Path("installations")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets installed artifacts", response = InstallArtifactInfo.class, responseContainer = "List")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Ok"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response getInstalledVersions() {
        try {
            Collection<InstallArtifactInfo> installedVersions = delegate.getInstalledVersions();
            return javax.ws.rs.core.Response.ok(installedVersions).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Updates Codenvy.
     */
    @POST
    @Path("update/" + CDECArtifact.NAME)
    @ApiOperation(value = "Updates Codenvy")
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Successfully updated"),
                           @ApiResponse(code = 400, message = "Binaries to install not found"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response updateCodenvy() {
        try {
            InstallType installType = configManager.detectInstallationType();
            CDECArtifact artifact = (CDECArtifact)ArtifactFactory.createArtifact(CDECArtifact.NAME);
            Version version = delegate.getLatestInstallableVersion(artifact);
            if (version == null) {
                return handleException(new IllegalStateException("There is no appropriate version to install"),
                                       javax.ws.rs.core.Response.Status.BAD_REQUEST);
            }
            Map<String, String> properties = configManager.prepareInstallProperties(null, installType, artifact, version);

            final InstallOptions installOptions = new InstallOptions();
            installOptions.setInstallType(installType);
            installOptions.setConfigProperties(properties);

            List<String> infos = delegate.getUpdateInfo(artifact, installType);
            for (int step = 0; step < infos.size(); step++) {
                installOptions.setStep(step);
                delegate.update(artifact, version, installOptions);
            }
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).build();
        } catch (FileNotFoundException e) {
            return handleException(e, javax.ws.rs.core.Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Gets Installation Manager configuration */
    @GET
    @Path("properties")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets Installation Manager Server configuration", response = Response.class)
    public javax.ws.rs.core.Response getInstallationManagerServerConfig() {
        Map<String, String> properties = delegate.getInstallationManagerProperties();
        return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(properties)).build();
    }

    /** Gets Codenvy nodes configuration */
    @GET
    @Path("nodes")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets Codenvy nodes configuration", response = Response.class)
    public javax.ws.rs.core.Response getNodesList() {
        try {
            InstallType installType = configManager.detectInstallationType();
            Config config = configManager.loadInstalledCodenvyConfig(installType);

            if (InstallType.SINGLE_SERVER.equals(installType)) {
                ImmutableMap<String, String> properties = ImmutableMap.of(Config.HOST_URL, config.getHostUrl());
                return javax.ws.rs.core.Response.ok(toJson(properties)).build();
            }

            Map<String, Object> selectedProperties = new HashMap<>();

            // filter node dns
            List<NodeConfig> nodes = NodeConfig.extractConfigsFrom(config);
            for (NodeConfig node : nodes) {
                String nodeHostPropertyName = node.getType().toString().toLowerCase() + Config.NODE_HOST_PROPERTY_SUFFIX;
                selectedProperties.put(nodeHostPropertyName, node.getHost());
            }

            // get additional nodes dns lists
            AdditionalNodesConfigUtil additionalNodesConfigUtil = new AdditionalNodesConfigUtil(config);
            Map<String, List<String>> additionalRunners = additionalNodesConfigUtil.extractAdditionalNodesDns(NodeConfig.NodeType.RUNNER);
            if (additionalRunners != null) {
                selectedProperties.putAll(additionalRunners);
            }

            Map<String, List<String>> additionalBuilders = additionalNodesConfigUtil.extractAdditionalNodesDns(NodeConfig.NodeType.BUILDER);
            if (additionalBuilders != null) {
                selectedProperties.putAll(additionalBuilders);
            }

            // add host url
            String hostUrl = config.getHostUrl();
            if (hostUrl != null) {
                selectedProperties.put(Config.HOST_URL, hostUrl);
            }

            return javax.ws.rs.core.Response.ok(toJson(selectedProperties)).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Adds Codenvy node in the multi-node environment.
     */
    @POST
    @Path("node")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Adds Codenvy node in the multi-node environment", response = NodeInfo.class)
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Successfully added"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response addNode(@QueryParam(value = "dns") @ApiParam(required = true, value = "node DNS to add") String dns) {
        try {
            NodeInfo nodeInfo = delegate.addNode(dns);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).entity(nodeInfo).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Removes Codenvy node in the multi-node environment
     */
    @DELETE
    @Path("node")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Removes Codenvy node in the multi-node environment")
    @ApiResponses(value = {@ApiResponse(code = 204, message = "Successfully removed"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response removeNode(@QueryParam(value = "dns") @ApiParam(required = true, value = "node DNS to remove") String dns) {
        try {
            delegate.removeNode(dns);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.NO_CONTENT).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Performs Codenvy backup.
     */
    @POST
    @Path("backup")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Performs Codenvy backup", response = BackupInfo.class)
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Successfully created"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response backup(@DefaultValue(CDECArtifact.NAME)
                                            @QueryParam(value = "artifact")
                                            @ApiParam(allowableValues = CDECArtifact.NAME) String artifactName) {

        try {
            BackupConfig config = new BackupConfig();
            config.setArtifactName(artifactName);
            config.setBackupDirectory(backupDir);

            BackupInfo backupInfo = delegate.backup(config);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).entity(backupInfo).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Performs Codenvy restoring.
     */
    @POST
    @Path("restore")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Performs Codenvy restoring", response = BackupInfo.class)
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Successfully restored"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response restore(@DefaultValue(CDECArtifact.NAME)
                                             @QueryParam(value = "artifact")
                                             @ApiParam(allowableValues = CDECArtifact.NAME) String artifactName,
                                             @QueryParam(value = "backupFile")
                                             @ApiParam(value = "path to backup file", required = true)
                                             String backupFile) throws IOException {

        try {
            BackupConfig config = new BackupConfig();
            config.setArtifactName(artifactName);
            config.setBackupFile(backupFile);

            BackupInfo backupInfo = delegate.restore(config);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).entity(backupInfo).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Adds trial subscription to account.
     */
    @POST
    @Path("subscription")
    @ApiOperation(value = "Adds trial subscription to account at the SaaS Codenvy")
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Subscription added"),
                           @ApiResponse(code = 403, message = "SaaS User is not authenticated or authentication token is expired"),
                           @ApiResponse(code = 500, message = "Server error")})
    public javax.ws.rs.core.Response addTrialSubscription() throws IOException, CloneNotSupportedException {
        if (saasUserCredentials == null) {
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.FORBIDDEN).build();
        }

        try {
            delegate.addTrialSaasSubscription(saasUserCredentials.clone());
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Gets OnPremises subscription for Codenvy SaaS user.
     * User has to be logged into Codenvy SaaS using {@link #loginToCodenvySaaS} method.
     */
    @GET
    @Path("subscription")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {@ApiResponse(code = 200, message = "OK"),
                           @ApiResponse(code = 403, message = "SaaS User is not authenticated or authentication token is expired"),
                           @ApiResponse(code = 404, message = "Subscription not found"),
                           @ApiResponse(code = 500, message = "Server error")})
    @ApiOperation(value = "Gets OnPremises subscription for Codenvy SaaS user", response = SubscriptionDescriptor.class)
    public javax.ws.rs.core.Response getOnPremisesSaasSubscription() {
        if (saasUserCredentials == null) {
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.FORBIDDEN).build();
        }

        try {
            SubscriptionDescriptor descriptor = delegate.getSaasSubscription(SaasAccountServiceProxy.ON_PREMISES, saasUserCredentials);
            if (descriptor == null) {
                return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.NOT_FOUND).build();
            }

            // remove useless info, since links point to Codenvy SaaS API
            descriptor.setLinks(null);

            return javax.ws.rs.core.Response.ok(toJson(descriptor)).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "Authentication failed"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Login to Codenvy SaaS",
            notes = "After login is successful SaaS user credentials will be cached.")
    public javax.ws.rs.core.Response loginToCodenvySaaS(Credentials credentials) {
        try {
            logoutFromCodenvySaaS();

            credentials = new DtoServerImpls.CredentialsImpl(credentials);

            Token token = delegate.loginToCodenvySaaS(credentials);
            SaasUserCredentials saasUserCredentials = new SaasUserCredentials(token.getValue());

            // get SaaS account id where user is owner
            AccountReference accountRef = delegate.getAccountWhereUserIsOwner(null, saasUserCredentials.getToken());
            if (accountRef == null) {
                throw new ApiException(SaasAccountServiceProxy.CANNOT_RECOGNISE_ACCOUNT_NAME_MSG);
            }
            saasUserCredentials.setAccountId(accountRef.getId());

            // cache SaaS user credentials into the state of service
            this.saasUserCredentials = saasUserCredentials;

            return javax.ws.rs.core.Response.ok().build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @POST
    @Path("logout")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Logout from Codenvy SaaS")
    public javax.ws.rs.core.Response logoutFromCodenvySaaS() {
        try {
            if (saasUserCredentials != null) {
                delegate.logoutFromCodenvySaaS(saasUserCredentials.getToken());
            }

            return javax.ws.rs.core.Response.ok().build();
        } catch (Exception e) {
            return handleException(e);
        } finally {
            saasUserCredentials = null;
        }
    }

    /** @return the properties of the specific artifact and version */
    @GET
    @Path("artifact/{artifact}/version/{version}/properties")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 404, message = "Artifact not found"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Gets list of the specific artifact and version properties")
    public javax.ws.rs.core.Response getArtifactProperties(@PathParam("artifact") final String artifactName,
                                                           @PathParam("version") final String artifactVersion) {
        try {
            Artifact artifact = createArtifact(artifactName);
            Version version;
            try {
                version = Version.valueOf(artifactVersion);
            } catch (IllegalArgumentException e) {
                throw new ArtifactNotFoundException(artifactName, artifactVersion);
            }

            Map<String, String> properties = artifact.getProperties(version);
            if (properties.containsKey(ArtifactProperties.BUILD_TIME_PROPERTY)) {
                String humanReadableDateTime = properties.get(ArtifactProperties.BUILD_TIME_PROPERTY);
                String valueInIso = convertToIsoDateTime(humanReadableDateTime);
                properties.put(ArtifactProperties.BUILD_TIME_PROPERTY, valueInIso);
            }

            return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(properties)).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Gets list of properties from the storage */
    @GET
    @Path("storage/properties")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Gets list of properties from the storage", response = Response.class)
    public javax.ws.rs.core.Response getStorageProperties() {
        try {
            Map<String, String> properties = delegate.loadStorageProperties();
            return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(properties)).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Inserts new properties into the storage and update existed */
    @POST
    @Path("storage/properties")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Inserts new properties into the storage and update existed")
    public javax.ws.rs.core.Response insertStorageProperties(Map<String, String> properties) {
        try {
            delegate.storeStorageProperties(properties);
            return javax.ws.rs.core.Response.ok().build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Get property value from the storage */
    @GET
    @Path("storage/properties/{key}")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 404, message = "Property not found"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Gets property value from the storage")
    public javax.ws.rs.core.Response getStorageProperty(@PathParam("key") String key) {
        try {
            String value = delegate.loadStorageProperty(key);
            return javax.ws.rs.core.Response.ok(value).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @PUT
    @Path("storage/properties/{key}")
    @Consumes("text/plain")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 404, message = "Property not found"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Updates property in the storage")
    public javax.ws.rs.core.Response updateStorageProperty(@PathParam("key") String key, String value) {
        try {
            delegate.storeStorageProperty(key, value);
            return javax.ws.rs.core.Response.ok().build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    @DELETE
    @Path("storage/properties/{key}")
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "No Content"),
            @ApiResponse(code = 404, message = "Property not found"),
            @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Deletes property from the storage")
    public javax.ws.rs.core.Response deleteStorageProperty(@PathParam("key") String key) {
        try {
            delegate.deleteStorageProperty(key);
            return javax.ws.rs.core.Response.noContent().build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Gets Codenvy configuration properties. */
    @GET
    @Path("codenvy/properties")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Gets Codenvy configuration properties")
    public javax.ws.rs.core.Response getCodenvyProperties() {
        try {
            Config config = configManager.loadInstalledCodenvyConfig();
            Map<String, String> properties = maskPrivateProperties(config.getProperties());
            return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(properties)).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Gets specific Codenvy configuration property. */
    @GET
    @Path("codenvy/properties/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 404, message = "Property not found"),
        @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Gets specific Codenvy configuration property")
    public javax.ws.rs.core.Response getCodenvyProperty(@PathParam("key") String key) {
        try {
            Config config = configManager.loadInstalledCodenvyConfig();
            Map<String, String> properties = maskPrivateProperties(config.getProperties());

            if (properties.containsKey(key)) {
                Map<String, String> m = ImmutableMap.of(key, properties.get(key));
                return javax.ws.rs.core.Response.ok(new JsonStringMapImpl<>(m)).build();
            } else {
                throw PropertyNotFoundException.from(key);
            }
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /** Updates Codenvy configuration property */
    @PUT
    @Path("codenvy/properties")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
        @ApiResponse(code = 201, message = "Successfully updated"),
        @ApiResponse(code = 500, message = "Unexpected error occurred")})
    @ApiOperation(value = "Updates property of configuration of Codenvy on-prem. It could take 5-7 minutes.")
    public javax.ws.rs.core.Response updateCodenvyProperties(Map<String, String> properties) {
        try {
            delegate.updateArtifactConfig(CDECArtifact.NAME, properties);
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.CREATED).build();
        } catch (Exception e) {
            return handleException(e);
        }
    }

    private javax.ws.rs.core.Response handleException(Exception e) {
        javax.ws.rs.core.Response.Status status;

        if (e instanceof ArtifactNotFoundException || e instanceof PropertyNotFoundException) {
            status = javax.ws.rs.core.Response.Status.NOT_FOUND;
        } else if (e instanceof HttpException) {
            status = javax.ws.rs.core.Response.Status.fromStatusCode(((HttpException)e).getStatus());
        } else if (e instanceof AuthenticationException
                   || e instanceof IllegalVersionException) {
            status = javax.ws.rs.core.Response.Status.BAD_REQUEST;
        } else {
            status = javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
        }

        return handleException(e, status);
    }

    private javax.ws.rs.core.Response handleException(Exception e, javax.ws.rs.core.Response.Status status) {
        LOG.error(e.getMessage(), e);

        JsonStringMapImpl<String> msgBody = new JsonStringMapImpl<>(ImmutableMap.of("message", e.getMessage()));
        return javax.ws.rs.core.Response.status(status)
                                        .entity(msgBody)
                                        .type(MediaType.APPLICATION_JSON_TYPE)
                                        .build();
    }

    /**
     * Convert string of datetime of format "yyyy-MM-dd HH:mm:ss" into ISO 8601 format "yyyy-MM-dd'T'HH:mm:ss.S'Z'"
     */
    private String convertToIsoDateTime(String humanReadableDateTime) throws ParseException {
        DateFormat dfInitial = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date initialDateTime = dfInitial.parse(humanReadableDateTime);

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S'Z'");

        return df.format(initialDateTime);
    }

    protected Artifact createArtifact(String artifactName) throws ArtifactNotFoundException {
        return ArtifactFactory.createArtifact(artifactName);
    }

    /**
     * @return map where all private keys ("password", "secret" etc.) were been updated to have PASSWORD_MASK value
     */
    private Map<String, String> maskPrivateProperties(Map<String, String> properties) {
        Map<String, String> maskedProperties = new HashMap<>(properties);
        for (Map.Entry<String, String> property : maskedProperties.entrySet()) {
            String key = property.getKey();
            if (isPrivateProperty(key)) {
                property.setValue(SECURE_VALUE_MASK);
            }
        }

        return maskedProperties;
    }

    /** @return true if only key matches one of the patterns from the PRIVATE_KEY_PATTERNS */
    private boolean isPrivateProperty(String key) {
        for (Pattern pattern: PRIVATE_KEY_PATTERNS) {
            Matcher m = pattern.matcher(key);
            if (m.find()) {
                return true;
            }
        }

        return false;
    }
}
