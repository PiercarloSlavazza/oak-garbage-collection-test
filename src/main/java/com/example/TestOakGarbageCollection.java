package com.example;

import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;
import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.core.data.DataStoreException;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.blob.cloud.s3.S3DataStore;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.blob.BlobGarbageCollector;
import org.apache.jackrabbit.oak.plugins.blob.MarkSweepGarbageCollector;
import org.apache.jackrabbit.oak.plugins.blob.MarkSweepGarbageCollector2;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.value.jcr.BlobsJcrDataRetriever;
import org.apache.jackrabbit.oak.segment.SegmentBlobReferenceRetriever;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.blob.FileBlobStore;
import org.apache.jackrabbit.oak.spi.blob.GarbageCollectableBlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.*;

import static java.lang.String.format;

interface TestOakGarbageCollectionConfig {

    @Option
    File getFileStorePath();

    @Option
    File getBlobStoreStorePath();

    @Option
    int getTestFileSizeInMegabytes();

    @Option
    TestOakGarbageCollection.BlobGarbageCollection getBlobGarbageCollection();

    @Option
    File getOakS3BlobStoreFolderPath();
    Boolean isOakS3BlobStoreFolderPath();

    @Option
    File getOakS3BlobStoreConfigFilePath();
    Boolean isOakS3BlobStoreConfigFilePath();
}

public class TestOakGarbageCollection {

    private static final Logger log = LoggerFactory.getLogger(TestOakGarbageCollection.class);

    public enum BlobGarbageCollection {
        LEGACY, JCR_DATA_SEARCH
    }

    private static void logFolderSize(File folder) {
        log.info(format("folder size in bytes|folder|%s|bytes|%d", folder, FileUtils.sizeOfDirectory(folder)));
    }

    private static File getTemporaryFileThatWillBeDeletedOnExit(String prefix, @SuppressWarnings("SameParameterValue") String suffix) throws IOException {
        File downloadFile = File.createTempFile(prefix, suffix);
        downloadFile.deleteOnExit();
        return downloadFile;
    }

    private static File createFileWithRandomContent(int fileSizeInBytes) throws IOException {
        byte[] bytes = new byte[fileSizeInBytes];
        BufferedOutputStream bufferedOutputStream = null;
        FileOutputStream fileOutputStream = null;

        File tempFile = getTemporaryFileThatWillBeDeletedOnExit("oak-garbage-collection-test-", ".bin");

        try {
            Random random = new Random();

            fileOutputStream = new FileOutputStream(tempFile);
            bufferedOutputStream = new BufferedOutputStream(fileOutputStream);

            random.nextBytes(bytes);
            bufferedOutputStream.write(bytes);

            bufferedOutputStream.flush();
            bufferedOutputStream.close();
            fileOutputStream.flush();
            fileOutputStream.close();

            return tempFile;
        } finally {
            if (bufferedOutputStream != null) {
                bufferedOutputStream.flush();
                bufferedOutputStream.close();
            }
            if (fileOutputStream != null) {
                fileOutputStream.flush();
                fileOutputStream.close();
            }
        }
    }

    private static NodeBuilder getNodeBuilder(Node node, NodeBuilder rootBuilder) throws RepositoryException {
        NodeBuilder nodeBuilder = rootBuilder;
        for (String name : PathUtils.elements(node.getPath())) {
            nodeBuilder = nodeBuilder.getChildNode(name);
        }
        return nodeBuilder;
    }

    public static Session loginAsAdmin(Repository repository) {
        try {
            return repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
        } catch (RepositoryException e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkThatAssertionsAreEnabled() {
        try {
            assert false;
            throw new RuntimeException("cannot start: please enable assertions by using the JVM option -ea");
        } catch (AssertionError ignored) {

        }
    }

    private static BlobGarbageCollector buildBlobGarbageCollector(
            BlobGarbageCollection blobGarbageCollection,
            FileStore fileStore,
            Repository repository,
            GarbageCollectableBlobStore fileBlobStore, Executor executorService) throws IOException {
        final String gcTempFolder = "./gc";
        final int batchCount = 1;
        final int maxLastModifiedInterval = 0;
        return switch (blobGarbageCollection) {
            case LEGACY -> new MarkSweepGarbageCollector(
                    new SegmentBlobReferenceRetriever(fileStore),
                    fileBlobStore,
                    executorService,
                    gcTempFolder,
                    batchCount, // do not have any idea of what this parameter is and what is supposed to be set
                    maxLastModifiedInterval, // Setting 0 will result in a log stating "Sweeping blobs with modified time > than the configured max deleted time (1970-01-01 01:00:00.000)"
                    "");
            case JCR_DATA_SEARCH -> new MarkSweepGarbageCollector2(
                    new BlobsJcrDataRetriever(() -> loginAsAdmin(repository)),
                    fileBlobStore,
                    executorService,
                    gcTempFolder,
                    batchCount,
                    maxLastModifiedInterval,
                    null);
        };
    }

    private static void compactFileStore(FileStore fileStore, SegmentGCOptions gcOptions) {
        for (int k = 0; k < gcOptions.getRetainedGenerations(); k++) {
            fileStore.compactFull();
        }
    }

    private static String addFile(NodeStore nodeStore, Repository repository, File temporaryFile) throws RepositoryException, IOException, CommitFailedException {
        String fileNodeId;
        Session session = loginAsAdmin(repository);
        try {
            Node rootFolder = session.getRootNode();
            Node fileNode = rootFolder.addNode(temporaryFile.getName(), "nt:file");
            fileNode.addMixin("mix:referenceable");
            Node fileContentNode = fileNode.addNode("jcr:content", "nt:resource");
            fileContentNode.setProperty("jcr:data", "");
            session.save();

            Blob blob = nodeStore.createBlob(FileUtils.openInputStream(temporaryFile));
            NodeBuilder rootBuilder = nodeStore.getRoot().builder();
            NodeBuilder fileContentNodeBuilder = getNodeBuilder(fileContentNode, rootBuilder);
            fileContentNodeBuilder.setProperty("jcr:data", blob);
            nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            session.save();

            fileNodeId = fileNode.getIdentifier();
        } finally {
            session.logout();
        }
        return fileNodeId;
    }

    private static FileBlobStore buildFileBlobStore(TestOakGarbageCollectionConfig config) {
        return new FileBlobStore(config.getBlobStoreStorePath().getAbsolutePath());
    }

    private static GarbageCollectableBlobStore buildS3BlobStore(File oakS3BlobStoreConfigFilePath, File oakS3BlobStoreFolderPath) {
        if (oakS3BlobStoreConfigFilePath == null || oakS3BlobStoreFolderPath == null)
            throw new IllegalArgumentException("oakS3BlobStoreConfigFilePath and oakS3BlobStoreFolderPath must be configured");
        if (!oakS3BlobStoreConfigFilePath.exists())
            throw new IllegalArgumentException("oakS3BlobStoreConfigFilePath not found|" + oakS3BlobStoreConfigFilePath);
        if (!oakS3BlobStoreConfigFilePath.isFile())
            throw new IllegalArgumentException("oakS3BlobStoreConfigFilePath not a file|" + oakS3BlobStoreConfigFilePath);
        if (!oakS3BlobStoreFolderPath.exists()) {
            try {
                FileUtils.forceMkdir(oakS3BlobStoreFolderPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (!oakS3BlobStoreFolderPath.isDirectory())
            throw new IllegalArgumentException("oakS3BlobStoreFolderPath not a folder|" + oakS3BlobStoreFolderPath);

        log.info(format("init blob store|type|S3|config|%s|folder|%s", oakS3BlobStoreConfigFilePath, oakS3BlobStoreFolderPath));

        Properties s3CfgFileProperties = new Properties();
        try (InputStream is = FileUtils.openInputStream(oakS3BlobStoreConfigFilePath)) {
            s3CfgFileProperties.load(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        S3DataStore s3DataStore = new S3DataStore();
        s3DataStore.setProperties(s3CfgFileProperties);

            /*
            We set MinRecordLength to zero bytes because
            we want that every file is uploaded to the blobstore regardless its size.
            */
        s3DataStore.setMinRecordLength(0);

            /*
            We disable the staging phase because, if enabled, it occurs the following issue:
            - when uploading a blob
            - the blob is properly uploaded to the blob store (e.g. S3)
            - BUT on subsequent execution it always occurs an error
            */
        s3DataStore.setStagingSplitPercentage(0);

        try {
            s3DataStore.init(oakS3BlobStoreFolderPath.getAbsolutePath());
        } catch (DataStoreException e) {
            throw new RuntimeException(e);
        }

        return new DataStoreBlobStore(s3DataStore);
    }

    private static GarbageCollectableBlobStore buildBlobStore(TestOakGarbageCollectionConfig config) {
        if (config.isOakS3BlobStoreFolderPath() && config.isOakS3BlobStoreConfigFilePath()) {
            log.info("Building S3 blob store");
            return buildS3BlobStore(config.getOakS3BlobStoreConfigFilePath(), config.getOakS3BlobStoreFolderPath());
        }

        log.info("Building file blob store");
        return buildFileBlobStore(config);
    }

    public static void main(String... args) throws Exception {
        checkThatAssertionsAreEnabled();

        TestOakGarbageCollectionConfig config = CliFactory.parseArguments(TestOakGarbageCollectionConfig.class, args);
        log.info("start|configs|" + config.toString());

        FileUtils.deleteQuietly(config.getFileStorePath());
        FileUtils.deleteQuietly(config.getBlobStoreStorePath());
        FileUtils.forceMkdir(config.getFileStorePath());
        FileUtils.forceMkdir(config.getBlobStoreStorePath());

        SegmentGCOptions gcOptions = SegmentGCOptions.defaultGCOptions().
                setEstimationDisabled(true);
        ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try (GarbageCollectableBlobStore blobStore = buildBlobStore(config);
             FileStore fileStore = FileStoreBuilder.
                     fileStoreBuilder(config.getFileStorePath()).
                     withBlobStore(blobStore).
                     withGCOptions(gcOptions).
                     build()) {
            NodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
            Repository repository = new Jcr(new Oak(nodeStore)).createRepository();

            BlobGarbageCollector garbageCollector = buildBlobGarbageCollector(
                    config.getBlobGarbageCollection(),
                    fileStore,
                    repository,
                    blobStore,
                    executorService);

            logFolderSize(config.getFileStorePath());
            logFolderSize(config.getBlobStoreStorePath());

            File temporaryFile = createFileWithRandomContent(config.getTestFileSizeInMegabytes() * 1024 * 1024);
            log.info(format("generated test file|file|%s|size in bytes|%d", temporaryFile.getAbsoluteFile(), FileUtils.sizeOf(temporaryFile)));
            long totalBlobsSize = FileUtils.sizeOf(temporaryFile);

            String fileNodeId;
            /*
            Create a random file of the given size just under the store root node
             */
            log.info("*****> adding file");
            fileNodeId = addFile(nodeStore, repository, temporaryFile);

            log.info("*****> run compaction");
            compactFileStore(fileStore, gcOptions);

            log.info("*****> run GC");
            fileStore.flush();
            garbageCollector.collectGarbage(false);

            logFolderSize(config.getFileStorePath());
            logFolderSize(config.getBlobStoreStorePath());

            /*
            Check that the file is actually there and the content is what we expect to be
             */
            log.info("*****> checking added file");
            Session session = loginAsAdmin(repository);
            try {
                Node fileNode = session.getNodeByIdentifier(fileNodeId);
                Node fileContentNode = fileNode.getNode("jcr:content");
                Property blobProperty = fileContentNode.getProperty("jcr:data");
                Binary blobBinary = blobProperty.getBinary();
                File downloadFile = getTemporaryFileThatWillBeDeletedOnExit("oak-garbage-collection-test-downloaded-", ".bin");
                FileUtils.copyInputStreamToFile(blobBinary.getStream(), downloadFile);
                blobBinary.dispose();
                final boolean isDownloadedFileEqualToOriginalFile = Files.mismatch(Paths.get(temporaryFile.getAbsolutePath()), Paths.get(downloadFile.getAbsolutePath())) == -1L;
                assert isDownloadedFileEqualToOriginalFile : format("downloaded file differs from original file|downloaded|%s|original|%s", downloadFile, temporaryFile);
            } finally {
                session.logout();
            }

            /*
            Delete the file
             */
            log.info("*****> deleting file");
            session = loginAsAdmin(repository);
            try {
                Node fileNode = session.getNodeByIdentifier(fileNodeId);
                fileNode.remove();
                session.save();
            } finally {
                session.logout();
            }

            /*
            Check the file is no more present
             */
            log.info("*****> check file removed from the store");
            session = loginAsAdmin(repository);
            try {
                try {
                    session.getNodeByIdentifier(fileNodeId);
                    assert false : "file is still present in the repository";
                } catch (ItemNotFoundException ignored) {
                }
            } finally {
                session.logout();
            }

            /*
            We must a "little" random content file because otherwise the repository will be void, and, as such, an error
            "Marked references not available" will be raised - see:
            https://issues.apache.org/jira/browse/OAK-9765?focusedCommentId=17534722&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-17534722
            and following for an explanation
             */
            addFile(nodeStore, repository, createFileWithRandomContent(1024 * 1024));

            /*
            See:
            https://issues.apache.org/jira/browse/OAK-9765?focusedCommentId=17534471&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-17534471
            and:
            https://issues.apache.org/jira/browse/OAK-9765?focusedCommentId=17534695&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-17534695
             */
            log.info("*****> run compaction");
            compactFileStore(fileStore, gcOptions);

            /*
            Run the GC: this is the tricky part, parameters _might_ be wrong
             */
            log.info("*****> run GC");
            fileStore.flush();
            garbageCollector.collectGarbage(false);

        } finally {
            executorService.shutdown();
        }

    }
}
