package codes.rafael.wellknown;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactUpload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.MetadataUpload;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.eclipse.aether.transfer.MetadataTransferException;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.aether.transfer.TransferResource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class WellKnownConnector implements RepositoryConnector {

    private static final String MAVEN = "maven", METADATA = "maven-metadata.xml";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9._+-]*");

    @FunctionalInterface
    public interface Connectors {

        RepositoryConnector connector(RepositorySystemSession session, RemoteRepository repository)
                throws NoRepositoryConnectorException;
    }

    private final RepositorySystemSession session;
    private final RemoteRepository repository;
    private final RepositoryConnector delegate;
    private final Discovery discovery;
    private final Connectors connectors;
    private final Map<URI, RepositoryConnector> roots = new ConcurrentHashMap<>();

    public WellKnownConnector(RepositorySystemSession session,
                              RemoteRepository repository,
                              RepositoryConnector delegate,
                              Discovery discovery,
                              Connectors connectors) {
        this.session = session;
        this.repository = repository;
        this.delegate = delegate;
        this.discovery = discovery;
        this.connectors = connectors;
    }

    @Override
    public void get(Collection<? extends ArtifactDownload> artifactDownloads,
                    Collection<? extends MetadataDownload> metadataDownloads) {
        List<ArtifactDownload> remaining = new ArrayList<>();
        if (artifactDownloads != null) {
            for (ArtifactDownload download : artifactDownloads) {
                if (!discovered(download)) {
                    remaining.add(download);
                }
            }
        }
        if (!remaining.isEmpty() || metadataDownloads != null && !metadataDownloads.isEmpty()) {
            delegate.get(remaining, metadataDownloads);
        }
        if (metadataDownloads != null) {
            for (MetadataDownload download : metadataDownloads) {
                Metadata metadata = download.getMetadata();
                if (METADATA.equals(metadata.getType())
                        && !metadata.getGroupId().isEmpty()
                        && !metadata.getArtifactId().isEmpty()
                        && metadata.getVersion().isEmpty()
                        && (download.getException() == null
                        || download.getException() instanceof MetadataNotFoundException)) {
                    overlay(download);
                }
            }
        }
    }

    @Override
    public void put(Collection<? extends ArtifactUpload> artifactUploads,
                    Collection<? extends MetadataUpload> metadataUploads) {
        delegate.put(artifactUploads, metadataUploads);
    }

    @Override
    public void close() {
        try {
            roots.values().forEach(RepositoryConnector::close);
        } finally {
            delegate.close();
        }
    }

    private boolean discovered(ArtifactDownload download) {
        Artifact artifact = download.getArtifact();
        try {
            DiscoveredLocation location = discovery.lookup(artifact.getGroupId(), MAVEN, artifact.getArtifactId())
                    .orElse(null);
            if (location == null || !location.admits(artifact.getVersion())) {
                return false;
            }
            if (!location.template()) {
                ArtifactDownload inner = new ArtifactDownload(artifact,
                        download.getRequestContext(),
                        download.getFile(),
                        download.getChecksumPolicy())
                        .setExistenceCheck(download.isExistenceCheck())
                        .setListener(download.getListener())
                        .setTrace(download.getTrace());
                root(location).get(List.of(inner), null);
                if (inner.getException() instanceof ArtifactNotFoundException) {
                    return false;
                }
                download.setException(inner.getException());
                return true;
            }
            String extension = artifact.getExtension().isEmpty() ? "jar" : artifact.getExtension();
            segment("artifact ID", artifact.getArtifactId());
            segment("version", artifact.getVersion());
            segment("extension", extension);
            if (!artifact.getClassifier().isEmpty()) {
                segment("classifier", artifact.getClassifier());
            }
            Map<String, String> values = new HashMap<>();
            values.put("groupId", artifact.getGroupId());
            values.put("groupPath", artifact.getGroupId().replace('.', '/'));
            values.put("artifactId", artifact.getArtifactId());
            values.put("version", artifact.getVersion());
            values.put("-classifier", artifact.getClassifier().isEmpty() ? "" : "-" + artifact.getClassifier());
            values.put("type", extension);
            URI uri = location.resolve(values, discovery.client().insecure()).orElse(null);
            if (uri == null) {
                return false;
            }
            URI base = uri.resolve(".");
            TransferResource resource = new TransferResource(location.domain(),
                    base.toString(),
                    base.relativize(uri).toString(),
                    download.getFile(),
                    download.getTrace());
            Transfer transfer = new Transfer(download.getListener(), resource, new TransferEvent.Builder(session, resource)
                    .setRequestType(download.isExistenceCheck()
                            ? TransferEvent.RequestType.GET_EXISTENCE
                            : TransferEvent.RequestType.GET));
            transfer.started();
            if (download.isExistenceCheck()) {
                if (!discovery.client().exists(uri)) {
                    transfer.failed(new IOException(uri + " does not exist"));
                    return false;
                }
                transfer.succeeded(new byte[0]);
                return true;
            }
            Optional<byte[]> bytes;
            try {
                bytes = location.fetch(uri, values, discovery.client());
            } catch (IOException | RuntimeException e) {
                transfer.failed(e);
                throw e;
            }
            if (bytes.isEmpty()) {
                transfer.failed(new IOException(uri + " does not exist"));
                return false;
            }
            transfer.progressed(bytes.get());
            write(download.getFile().toPath(), bytes.get());
            transfer.succeeded(bytes.get());
            return true;
        } catch (IOException | RuntimeException | NoRepositoryConnectorException | TransferCancelledException e) {
            download.setException(new ArtifactTransferException(artifact, repository,
                    "Could not transfer " + artifact + " from the location its domain discovered: " + e.getMessage(),
                    e));
            return true;
        }
    }

    private void overlay(MetadataDownload download) {
        Metadata metadata = download.getMetadata();
        try {
            DiscoveredLocation location = discovery.lookup(metadata.getGroupId(), MAVEN, metadata.getArtifactId())
                    .orElse(null);
            if (location == null) {
                return;
            }
            VersionListing discovered;
            if (location.template()) {
                segment("artifact ID", metadata.getArtifactId());
                Map<String, String> values = Map.of("groupId", metadata.getGroupId(),
                        "groupPath", metadata.getGroupId().replace('.', '/'),
                        "artifactId", metadata.getArtifactId());
                if (location.listsVersions()) {
                    discovered = location.metadata(values, discovery.client()).orElse(null);
                } else {
                    String version = location.latest(values, discovery.client()).orElse(null);
                    discovered = version == null || !location.admits(version)
                            ? null
                            : VersionListing.of(metadata.getGroupId(), metadata.getArtifactId(), version);
                }
            } else {
                Path parent = download.getFile().toPath().toAbsolutePath().getParent();
                Files.createDirectories(parent);
                Path temporary = Files.createTempFile(parent, "wellknown-", ".xml");
                try {
                    MetadataDownload inner = new MetadataDownload(metadata,
                            download.getRequestContext(),
                            temporary.toFile(),
                            download.getChecksumPolicy())
                            .setListener(download.getListener())
                            .setTrace(download.getTrace());
                    root(location).get(null, List.of(inner));
                    if (inner.getException() instanceof MetadataNotFoundException) {
                        discovered = null;
                    } else if (inner.getException() != null) {
                        throw new IOException(inner.getException().getMessage(), inner.getException());
                    } else {
                        VersionListing listed = VersionListing.parse(Files.readAllBytes(temporary));
                        discovered = location.restricted() ? listed.filter(location::admits) : listed;
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (discovered == null || discovered.versions().isEmpty()) {
                return;
            }
            Path file = download.getFile().toPath();
            VersionListing merged = download.getException() == null && Files.isRegularFile(file)
                    ? VersionListing.parse(Files.readAllBytes(file)).merge(discovered)
                    : discovered;
            write(file, merged.toBytes());
            download.setException(null);
        } catch (IOException | RuntimeException | NoRepositoryConnectorException e) {
            download.setException(new MetadataTransferException(metadata, repository,
                    "Could not read the versions of " + metadata + " from the location its domain discovered: "
                            + e.getMessage(),
                    e));
        }
    }

    private RepositoryConnector root(DiscoveredLocation location) throws NoRepositoryConnectorException {
        URI root = location.root(discovery.client().insecure());
        RepositoryConnector connector = roots.get(root);
        if (connector == null) {
            RemoteRepository repository = new RemoteRepository.Builder("wellknown-" + location.domain(),
                    "default",
                    root.toString()).build();
            if (session.getProxySelector() != null) {
                repository = new RemoteRepository.Builder(repository)
                        .setProxy(session.getProxySelector().getProxy(repository))
                        .build();
            }
            connector = connectors.connector(session, repository);
            RepositoryConnector previous = roots.putIfAbsent(root, connector);
            if (previous != null) {
                connector.close();
                connector = previous;
            }
        }
        return connector;
    }

    private static void segment(String name, String value) {
        if (!SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("The " + name + " '" + value + "' cannot be placed in a template,"
                    + " which takes letters, digits, '.', '_', '+' and '-' and no leading '.'");
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".wellknown");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public String toString() {
        return "wellknown(" + delegate + ")";
    }

    private record Transfer(TransferListener listener, TransferResource resource, TransferEvent.Builder event) {

        void started() throws TransferCancelledException {
            if (listener != null) {
                listener.transferInitiated(event.copy().setType(TransferEvent.EventType.INITIATED).build());
                listener.transferStarted(event.copy().setType(TransferEvent.EventType.STARTED).build());
            }
        }

        void progressed(byte[] bytes) throws TransferCancelledException {
            if (listener != null) {
                resource.setContentLength(bytes.length);
                listener.transferProgressed(event.copy()
                        .setType(TransferEvent.EventType.PROGRESSED)
                        .setTransferredBytes(bytes.length)
                        .setDataBuffer(bytes, 0, bytes.length)
                        .build());
            }
        }

        void succeeded(byte[] bytes) {
            if (listener != null) {
                resource.setContentLength(bytes.length);
                listener.transferSucceeded(event.copy()
                        .setType(TransferEvent.EventType.SUCCEEDED)
                        .setTransferredBytes(bytes.length)
                        .build());
            }
        }

        void failed(Exception exception) {
            if (listener != null) {
                listener.transferFailed(event.copy()
                        .setType(TransferEvent.EventType.FAILED)
                        .setException(exception)
                        .build());
            }
        }
    }
}
