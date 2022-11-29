/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.actions;

import java.nio.file.Path;
import java.util.List;

import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.GalleonArtifactExporter;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.updates.UpdateFinder;
import org.wildfly.prospero.updates.UpdateSet;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;

public class UpdateAction implements AutoCloseable {

    private final InstallationMetadata metadata;
    private final MavenSessionManager mavenSessionManager;
    private final GalleonEnvironment galleonEnv;
    private final ProsperoConfig prosperoConfig;
    private final Path installDir;

    public UpdateAction(Path installDir, MavenSessionManager mavenSessionManager, Console console, List<Repository> overrideRepositories)
            throws ProvisioningException, OperationException {
        this.metadata = new InstallationMetadata(installDir);

        this.prosperoConfig = addTemporaryRepositories(overrideRepositories);
        galleonEnv = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager)
                .setConsole(console)
                .build();
        this.installDir = installDir;
        this.mavenSessionManager = mavenSessionManager;
    }

    public void performUpdate() throws ProvisioningException, MetadataException, ArtifactResolutionException {
        if (findUpdates().isEmpty()) {
            return;
        }

        applyUpdates();

        metadata.recordProvision(false);
    }

    public UpdateSet findUpdates() throws ArtifactResolutionException, ProvisioningException {
        try (final UpdateFinder updateFinder = new UpdateFinder(galleonEnv.getChannelSession(), galleonEnv.getProvisioningManager())) {
            return updateFinder.findUpdates(metadata.getArtifacts());
        }
    }

    @Override
    public void close() {
        metadata.close();
    }

    private ProsperoConfig addTemporaryRepositories(List<Repository> repositories) {
        final ProsperoConfig prosperoConfig = metadata.getProsperoConfig();

        final List<Channel> channels = TemporaryRepositoriesHandler.overrideRepositories(prosperoConfig.getChannels(), repositories);

        return new ProsperoConfig(channels);
    }

    private void applyUpdates() throws ProvisioningException, ArtifactResolutionException {
        final ProvisioningManager provMgr = galleonEnv.getProvisioningManager();
        try {
            GalleonUtils.executeGalleon(options -> provMgr.provision(provMgr.getProvisioningConfig(), options),
                    mavenSessionManager.getProvisioningRepo().toAbsolutePath());
        } catch (UnresolvedMavenArtifactException e) {
            throw new ArtifactResolutionException(e, prosperoConfig.listAllRepositories(), mavenSessionManager.isOffline());
        }

        metadata.setManifest(galleonEnv.getRepositoryManager().resolvedChannel());

        try {
            new GalleonArtifactExporter().cacheGalleonArtifacts(galleonEnv.getChannels(), mavenSessionManager, installDir, provMgr.getProvisioningConfig());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
