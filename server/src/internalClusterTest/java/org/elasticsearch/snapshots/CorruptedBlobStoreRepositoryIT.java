/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.snapshots;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.RepositoriesMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.IndexMetaDataGenerations;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.ShardGenerations;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFileExists;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class CorruptedBlobStoreRepositoryIT extends AbstractSnapshotIntegTestCase {

    public void testConcurrentlyChangeRepositoryContents() throws Exception {
        Client client = client();

        Path repo = randomRepoPath();
        final String repoName = "test-repo";
        logger.info("-->  creating repository at {}", repo.toAbsolutePath());
        assertAcked(client.admin().cluster().preparePutRepository(repoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                .put("compress", false)
                // Don't cache repository data because the test manually modifies the repository data
                .put(BlobStoreRepository.CACHE_REPOSITORY_DATA.getKey(), false)
                .put("chunk_size", randomIntBetween(100, 1000), ByteSizeUnit.BYTES)));

        createIndex("test-idx-1", "test-idx-2");
        logger.info("--> indexing some data");
        indexRandom(true,
            client().prepareIndex("test-idx-1").setSource("foo", "bar"),
            client().prepareIndex("test-idx-2").setSource("foo", "bar"));

        final String snapshot = "test-snap";

        logger.info("--> creating snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot(repoName, snapshot)
            .setWaitForCompletion(true).setIndices("test-idx-*").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        logger.info("--> move index-N blob to next generation");
        final RepositoryData repositoryData =
            getRepositoryData(internalCluster().getMasterNodeInstance(RepositoriesService.class).repository(repoName));
        Files.move(repo.resolve("index-" + repositoryData.getGenId()), repo.resolve("index-" + (repositoryData.getGenId() + 1)));

        assertRepositoryBlocked(client, repoName, snapshot);

        if (randomBoolean()) {
            logger.info("--> move index-N blob back to initial generation");
            Files.move(repo.resolve("index-" + (repositoryData.getGenId() + 1)), repo.resolve("index-" + repositoryData.getGenId()));

            logger.info("--> verify repository remains blocked");
            assertRepositoryBlocked(client, repoName, snapshot);
        }

        logger.info("--> remove repository");
        assertAcked(client.admin().cluster().prepareDeleteRepository(repoName));

        logger.info("--> recreate repository");
        assertAcked(client.admin().cluster().preparePutRepository(repoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                .put("compress", false)
                .put("chunk_size", randomIntBetween(100, 1000), ByteSizeUnit.BYTES)));

        logger.info("--> delete snapshot");
        client.admin().cluster().prepareDeleteSnapshot(repoName, snapshot).get();

        logger.info("--> make sure snapshot doesn't exist");
        expectThrows(SnapshotMissingException.class, () -> client.admin().cluster().prepareGetSnapshots(repoName)
            .addSnapshots(snapshot).get().getSnapshots(repoName));
    }

    public void testFindDanglingLatestGeneration() throws Exception {
        Path repo = randomRepoPath();
        final String repoName = "test-repo";
        logger.info("-->  creating repository at {}", repo.toAbsolutePath());
        assertAcked(client().admin().cluster().preparePutRepository(repoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                .put("compress", false)
                .put("chunk_size", randomIntBetween(100, 1000), ByteSizeUnit.BYTES)));

        createIndex("test-idx-1", "test-idx-2");
        logger.info("--> indexing some data");
        indexRandom(true,
            client().prepareIndex("test-idx-1").setSource("foo", "bar"),
            client().prepareIndex("test-idx-2").setSource("foo", "bar"));

        final String snapshot = "test-snap";

        logger.info("--> creating snapshot");
        CreateSnapshotResponse createSnapshotResponse = client().admin().cluster().prepareCreateSnapshot(repoName, snapshot)
            .setWaitForCompletion(true).setIndices("test-idx-*").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        final Repository repository = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(repoName);

        logger.info("--> move index-N blob to next generation");
        final RepositoryData repositoryData = getRepositoryData(repository);
        final long beforeMoveGen = repositoryData.getGenId();
        Files.move(repo.resolve("index-" + beforeMoveGen), repo.resolve("index-" + (beforeMoveGen + 1)));

        logger.info("--> set next generation as pending in the cluster state");
        final PlainActionFuture<Void> csUpdateFuture = PlainActionFuture.newFuture();
        internalCluster().getCurrentMasterNodeInstance(ClusterService.class).submitStateUpdateTask("set pending generation",
            new ClusterStateUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return ClusterState.builder(currentState).metadata(Metadata.builder(currentState.getMetadata())
                        .putCustom(RepositoriesMetadata.TYPE,
                            currentState.metadata().<RepositoriesMetadata>custom(RepositoriesMetadata.TYPE).withUpdatedGeneration(
                                repository.getMetadata().name(), beforeMoveGen, beforeMoveGen + 1)).build()).build();
                }

                @Override
                public void onFailure(String source, Exception e) {
                    csUpdateFuture.onFailure(e);
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    csUpdateFuture.onResponse(null);
                }
            }
        );
        csUpdateFuture.get();

        logger.info("--> full cluster restart");
        internalCluster().fullRestart();
        ensureGreen();

        Repository repositoryAfterRestart = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(repoName);

        logger.info("--> verify index-N blob is found at the new location");
        assertThat(getRepositoryData(repositoryAfterRestart).getGenId(), is(beforeMoveGen + 1));

        logger.info("--> delete snapshot");
        client().admin().cluster().prepareDeleteSnapshot(repoName, snapshot).get();

        logger.info("--> verify index-N blob is found at the expected location");
        assertThat(getRepositoryData(repositoryAfterRestart).getGenId(), is(beforeMoveGen + 2));

        logger.info("--> make sure snapshot doesn't exist");
        expectThrows(SnapshotMissingException.class, () -> client().admin().cluster().prepareGetSnapshots(repoName)
            .addSnapshots(snapshot).get().getSnapshots(repoName));
    }

    public void testHandlingMissingRootLevelSnapshotMetadata() throws Exception {
        Path repo = randomRepoPath();
        final String repoName = "test-repo";
        logger.info("-->  creating repository at {}", repo.toAbsolutePath());
        assertAcked(client().admin().cluster().preparePutRepository(repoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                .put("compress", false)
                // Don't cache repository data because the test manually modifies the repository data
                .put(BlobStoreRepository.CACHE_REPOSITORY_DATA.getKey(), false)
                .put("chunk_size", randomIntBetween(100, 1000), ByteSizeUnit.BYTES)));

        final String snapshotPrefix = "test-snap-";
        final int snapshots = randomIntBetween(1, 2);
        logger.info("--> creating [{}] snapshots", snapshots);
        for (int i = 0; i < snapshots; ++i) {
            // Workaround to simulate BwC situation: taking a snapshot without indices here so that we don't create any new version shard
            // generations (the existence of which would short-circuit checks for the repo containing old version snapshots)
            CreateSnapshotResponse createSnapshotResponse = client().admin().cluster().prepareCreateSnapshot(repoName, snapshotPrefix + i)
                .setIndices().setWaitForCompletion(true).get();
            assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), is(0));
            assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(),
                equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));
        }
        final Repository repository = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(repoName);
        final RepositoryData repositoryData = getRepositoryData(repository);

        final SnapshotId snapshotToCorrupt = randomFrom(repositoryData.getSnapshotIds());
        logger.info("--> delete root level snapshot metadata blob for snapshot [{}]", snapshotToCorrupt);
        Files.delete(repo.resolve(String.format(Locale.ROOT, BlobStoreRepository.SNAPSHOT_NAME_FORMAT, snapshotToCorrupt.getUUID())));

        logger.info("--> strip version information from index-N blob");
        final RepositoryData withoutVersions = new RepositoryData(repositoryData.getGenId(),
            repositoryData.getSnapshotIds().stream().collect(Collectors.toMap(
                SnapshotId::getUUID, Function.identity())),
            repositoryData.getSnapshotIds().stream().collect(Collectors.toMap(
                SnapshotId::getUUID, repositoryData::getSnapshotState)),
            Collections.emptyMap(), Collections.emptyMap(), ShardGenerations.EMPTY, IndexMetaDataGenerations.EMPTY);

        Files.write(repo.resolve(BlobStoreRepository.INDEX_FILE_PREFIX + withoutVersions.getGenId()),
            BytesReference.toBytes(BytesReference.bytes(
                withoutVersions.snapshotsToXContent(XContentFactory.jsonBuilder(), Version.CURRENT))),
            StandardOpenOption.TRUNCATE_EXISTING);

        logger.info("--> verify that repo is assumed in old metadata format");
        final SnapshotsService snapshotsService = internalCluster().getCurrentMasterNodeInstance(SnapshotsService.class);
        final ThreadPool threadPool = internalCluster().getCurrentMasterNodeInstance(ThreadPool.class);
        assertThat(PlainActionFuture.get(f -> threadPool.generic().execute(
            ActionRunnable.supply(f, () ->
                snapshotsService.minCompatibleVersion(Version.CURRENT, getRepositoryData(repository), null)))),
            is(SnapshotsService.OLD_SNAPSHOT_FORMAT));

        logger.info("--> verify that snapshot with missing root level metadata can be deleted");
        assertAcked(client().admin().cluster().prepareDeleteSnapshot(repoName, snapshotToCorrupt.getName()).get());

        logger.info("--> verify that repository is assumed in new metadata format after removing corrupted snapshot");
        assertThat(PlainActionFuture.get(f -> threadPool.generic().execute(
            ActionRunnable.supply(f, () ->
                snapshotsService.minCompatibleVersion(Version.CURRENT, getRepositoryData(repository), null)))),
            is(Version.CURRENT));
        final RepositoryData finalRepositoryData = getRepositoryData(repository);
        for (SnapshotId snapshotId : finalRepositoryData.getSnapshotIds()) {
            assertThat(finalRepositoryData.getVersion(snapshotId), is(Version.CURRENT));
        }
    }

    public void testMountCorruptedRepositoryData() throws Exception {
        disableRepoConsistencyCheck("This test intentionally corrupts the repository contents");
        Client client = client();

        Path repo = randomRepoPath();
        final String repoName = "test-repo";
        logger.info("-->  creating repository at {}", repo.toAbsolutePath());
        assertAcked(client.admin().cluster().preparePutRepository(repoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                // Don't cache repository data because the test manually modifies the repository data
                .put(BlobStoreRepository.CACHE_REPOSITORY_DATA.getKey(), false)
                .put("compress", false)));

        final String snapshot = "test-snap";

        logger.info("--> creating snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin().cluster().prepareCreateSnapshot(repoName, snapshot)
            .setWaitForCompletion(true).setIndices("test-idx-*").get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards()));

        logger.info("--> corrupt index-N blob");
        final Repository repository = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(repoName);
        final RepositoryData repositoryData = getRepositoryData(repository);
        Files.write(repo.resolve("index-" + repositoryData.getGenId()), randomByteArrayOfLength(randomIntBetween(1, 100)));

        logger.info("--> verify loading repository data throws RepositoryException");
        expectThrows(RepositoryException.class, () -> getRepositoryData(repository));

        logger.info("--> mount repository path in a new repository");
        final String otherRepoName = "other-repo";
        assertAcked(client.admin().cluster().preparePutRepository(otherRepoName)
            .setType("fs").setSettings(Settings.builder()
                .put("location", repo)
                .put("compress", false)));
        final Repository otherRepo = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(otherRepoName);

        logger.info("--> verify loading repository data from newly mounted repository throws RepositoryException");
        expectThrows(RepositoryException.class, () -> getRepositoryData(otherRepo));
    }

    public void testHandleSnapshotErrorWithBwCFormat() throws IOException {
        final String repoName = "test-repo";
        final Path repoPath = randomRepoPath();
        createRepository(repoName, "fs", repoPath);

        // Workaround to simulate BwC situation: taking a snapshot without indices here so that we don't create any new version shard
        // generations (the existence of which would short-circuit checks for the repo containing old version snapshots)
        final String oldVersionSnapshot = "old-version-snapshot";
        final CreateSnapshotResponse createSnapshotResponse = client().admin().cluster()
                .prepareCreateSnapshot(repoName, oldVersionSnapshot).setIndices().setWaitForCompletion(true).get();
        assertThat(createSnapshotResponse.getSnapshotInfo().totalShards(), is(0));

        logger.info("--> writing downgraded RepositoryData");
        final RepositoryData repositoryData = getRepositoryData(repoName);
        final XContentBuilder jsonBuilder = JsonXContent.contentBuilder();
        repositoryData.snapshotsToXContent(jsonBuilder, SnapshotsService.OLD_SNAPSHOT_FORMAT);
        final RepositoryData downgradedRepoData = RepositoryData.snapshotsFromXContent(JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                Strings.toString(jsonBuilder).replace(Version.CURRENT.toString(), SnapshotsService.OLD_SNAPSHOT_FORMAT.toString())),
                repositoryData.getGenId());
        Files.write(repoPath.resolve(BlobStoreRepository.INDEX_FILE_PREFIX + repositoryData.getGenId()),
                BytesReference.toBytes(BytesReference.bytes(
                        downgradedRepoData.snapshotsToXContent(XContentFactory.jsonBuilder(), SnapshotsService.OLD_SNAPSHOT_FORMAT))),
                StandardOpenOption.TRUNCATE_EXISTING);

        logger.info("--> recreating repository to clear caches");
        client().admin().cluster().prepareDeleteRepository(repoName).get();
        createRepository(repoName, "fs", repoPath);

        final String indexName = "test-index";
        createIndex(indexName);

        assertCreateSnapshotSuccess(repoName, "snapshot-1");

        // In the old metadata version the shard level metadata could be moved to the next generation for all sorts of reasons, this should
        // not break subsequent repository operations
        logger.info("--> move shard level metadata to new generation");
        final IndexId indexId = getRepositoryData(repoName).resolveIndexId(indexName);
        final Path shardPath = repoPath.resolve("indices").resolve(indexId.getId()).resolve("0");
        final Path initialShardMetaPath = shardPath.resolve(BlobStoreRepository.INDEX_FILE_PREFIX + "0");
        assertFileExists(initialShardMetaPath);
        Files.move(initialShardMetaPath, shardPath.resolve(BlobStoreRepository.INDEX_FILE_PREFIX + "1"));

        logger.info("--> delete old version snapshot");
        client().admin().cluster().prepareDeleteSnapshot(repoName, oldVersionSnapshot).get();

        assertCreateSnapshotSuccess(repoName, "snapshot-2");
    }

    private void assertCreateSnapshotSuccess(String repoName, String snapshotName) {
        logger.info("--> create another snapshot");
        final SnapshotInfo snapshotInfo = client().admin().cluster().prepareCreateSnapshot(repoName, snapshotName)
                .setWaitForCompletion(true).get().getSnapshotInfo();
        assertThat(snapshotInfo.state(), is(SnapshotState.SUCCESS));
        final int successfulShards = snapshotInfo.successfulShards();
        assertThat(successfulShards, greaterThan(0));
        assertThat(successfulShards, equalTo(snapshotInfo.totalShards()));
    }

    private void assertRepositoryBlocked(Client client, String repo, String existingSnapshot) {
        logger.info("--> try to delete snapshot");
        final RepositoryException repositoryException3 = expectThrows(RepositoryException.class,
            () -> client.admin().cluster().prepareDeleteSnapshot(repo, existingSnapshot).execute().actionGet());
        assertThat(repositoryException3.getMessage(),
            containsString("Could not read repository data because the contents of the repository do not match its expected state."));

        logger.info("--> try to create snapshot");
        final RepositoryException repositoryException4 = expectThrows(RepositoryException.class,
            () -> client.admin().cluster().prepareCreateSnapshot(repo, existingSnapshot).execute().actionGet());
        assertThat(repositoryException4.getMessage(),
            containsString("Could not read repository data because the contents of the repository do not match its expected state."));
    }
}
