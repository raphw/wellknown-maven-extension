package codes.rafael.wellknown;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactUpload;
import org.eclipse.aether.spi.connector.MetadataDownload;
import org.eclipse.aether.spi.connector.MetadataUpload;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WellKnownConnectorTest {

    private static final RemoteRepository CENTRAL = new RemoteRepository.Builder("central",
            "default",
            "https://repo.maven.apache.org/maven2/").build();

    @TempDir
    Path folder;

    private DiscoveryServer server;
    private RecordingConnector delegate;
    private RecordingConnector root;
    private List<RemoteRepository> roots;

    @BeforeEach
    void setUp() throws IOException {
        server = new DiscoveryServer();
        delegate = new RecordingConnector(CENTRAL);
        roots = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void serves_an_artifact_from_its_template_and_checks_it_against_its_checksum() throws Exception {
        template();
        server.serve("/releases/v1.0.0/lib-1.0.0.jar", "jar");
        server.serve("/releases/v1.0.0/lib-1.0.0.jar.sha256", sha256("jar") + "  lib-1.0.0.jar");
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(download.getException()).isNull();
        assertThat(Files.readString(download.getFile().toPath())).isEqualTo("jar");
        assertThat(delegate.asked).as("the domain answers before the repository").isEmpty();
    }

    @Test
    void fails_an_artifact_that_does_not_match_its_checksum() {
        template();
        server.serve("/releases/v1.0.0/lib-1.0.0.jar", "jar");
        server.serve("/releases/v1.0.0/lib-1.0.0.jar.sha512", "0000");
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(download.getException()).hasMessageContaining("Failed checksum validation");
        assertThat(download.getFile()).doesNotExist();
        assertThat(delegate.asked).isEmpty();
    }

    @Test
    void leaves_an_artifact_the_template_does_not_hold_to_the_repository() {
        template();
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(delegate.asked).containsExactly("com.example:lib:jar:1.0.0");
        assertThat(download.getException()).isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void leaves_a_classifier_a_template_cannot_name_to_the_repository() {
        server.file("example.com", "maven=" + server.uri("/releases/v{version}/{artifactId}-{version}.{type}"));
        ArtifactDownload download = download("com.example:lib:jar:sources:1.0.0");

        connector().get(List.of(download), null);

        assertThat(delegate.asked).containsExactly("com.example:lib:jar:sources:1.0.0");
        assertThat(server.requested()).containsExactly("GET /example.com/java-repository.properties");
    }

    @Test
    void leaves_a_version_its_entry_does_not_admit_to_the_repository() {
        template();
        ArtifactDownload download = download("com.example:lib:1.0.0-SNAPSHOT");

        connector().get(List.of(download), null);

        assertThat(delegate.asked).containsExactly("com.example:lib:jar:1.0.0-SNAPSHOT");
        assertThat(server.requested()).containsExactly("GET /example.com/java-repository.properties");
    }

    @Test
    void leaves_a_group_whose_domain_publishes_nothing_to_the_repository() {
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(delegate.asked).containsExactly("com.example:lib:jar:1.0.0");
    }

    @Test
    void merges_the_version_a_latest_link_redirects_to_into_the_metadata_of_the_repository() throws Exception {
        template();
        server.answer("/releases/latest/lib.pom", 302, Map.of("Location", "/releases/v2.0.0/lib.pom"));
        delegate.metadata.put("com.example:lib", metadata("1.0.0"));
        MetadataDownload download = metadataDownload("com.example", "lib");

        connector().get(null, List.of(download));

        assertThat(download.getException()).isNull();
        VersionListing listing = VersionListing.parse(Files.readAllBytes(download.getFile().toPath()));
        assertThat(listing.versions()).containsExactly("1.0.0", "2.0.0");
        assertThat(listing.release()).isEqualTo("2.0.0");
        assertThat(listing.latest()).isEqualTo("2.0.0");
    }

    @Test
    void answers_the_metadata_a_repository_does_not_hold() throws Exception {
        template();
        server.answer("/releases/latest/lib.pom", 302, Map.of("Location", "/releases/v2.0.0/lib.pom"));
        MetadataDownload download = metadataDownload("com.example", "lib");

        connector().get(null, List.of(download));

        assertThat(download.getException()).isNull();
        assertThat(VersionListing.parse(Files.readAllBytes(download.getFile().toPath())).versions())
                .containsExactly("2.0.0");
    }

    @Test
    void reads_the_version_a_latest_link_announces_in_its_header() throws Exception {
        template();
        server.answer("/releases/latest/lib.pom", 200, Map.of("Jenesis-MavenVersion", "3.0.0"));
        MetadataDownload download = metadataDownload("com.example", "lib");

        connector().get(null, List.of(download));

        assertThat(VersionListing.parse(Files.readAllBytes(download.getFile().toPath())).versions())
                .containsExactly("3.0.0");
    }

    @Test
    void reads_the_versions_a_latest_link_to_maven_metadata_lists() throws Exception {
        server.file("example.com",
                "maven=" + server.uri("/releases/v{version}/{artifactId}-{version}{-classifier}.{type}"),
                "maven.latest=" + server.uri("/listing/{artifactId}/maven-metadata.xml"),
                "maven.suffixes=none");
        server.serve("/listing/lib/maven-metadata.xml", metadata("1.0.0", "2.0.0-SNAPSHOT", "2.0.0"));
        MetadataDownload download = metadataDownload("com.example", "lib");

        connector().get(null, List.of(download));

        VersionListing listing = VersionListing.parse(Files.readAllBytes(download.getFile().toPath()));
        assertThat(listing.versions()).containsExactly("1.0.0", "2.0.0");
        assertThat(listing.release()).isEqualTo("2.0.0");
    }

    @Test
    void reads_a_root_through_a_connector_of_its_own() throws Exception {
        server.file("example.com", "maven=https://maven.example.com/releases");
        root = new RecordingConnector(null);
        root.artifacts.put("com.example:lib:jar:1.0.0", "jar".getBytes(StandardCharsets.UTF_8));
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(download.getException()).isNull();
        assertThat(Files.readString(download.getFile().toPath())).isEqualTo("jar");
        assertThat(roots).extracting(RemoteRepository::getUrl).containsExactly("https://maven.example.com/releases/");
        assertThat(delegate.asked).isEmpty();
    }

    @Test
    void leaves_an_artifact_a_root_does_not_hold_to_the_repository() {
        server.file("example.com", "maven=https://maven.example.com/releases/");
        root = new RecordingConnector(null);
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(root.asked).containsExactly("com.example:lib:jar:1.0.0");
        assertThat(delegate.asked).containsExactly("com.example:lib:jar:1.0.0");
    }

    @Test
    void merges_the_versions_of_a_root_its_entry_admits() throws Exception {
        server.file("example.com", "maven=https://maven.example.com/releases/", "maven.since=2.0.0");
        root = new RecordingConnector(null);
        root.metadata.put("com.example:lib", metadata("1.5.0", "2.0.0", "2.1.0"));
        delegate.metadata.put("com.example:lib", metadata("1.0.0"));
        MetadataDownload download = metadataDownload("com.example", "lib");

        connector().get(null, List.of(download));

        VersionListing listing = VersionListing.parse(Files.readAllBytes(download.getFile().toPath()));
        assertThat(listing.versions()).containsExactly("1.0.0", "2.0.0", "2.1.0");
        assertThat(listing.release()).isEqualTo("2.1.0");
    }

    @Test
    void fails_an_artifact_whose_domain_publishes_a_broken_file() {
        server.file("example.com", "maven=com.example:lib");
        ArtifactDownload download = download("com.example:lib:1.0.0");

        connector().get(List.of(download), null);

        assertThat(download.getException()).hasMessageContaining("where a location is expected");
        assertThat(delegate.asked).isEmpty();
    }

    @Test
    void wraps_the_connector_of_the_factory_with_the_highest_priority() throws Exception {
        RecordingFactory low = new RecordingFactory(0), high = new RecordingFactory(10);
        WellKnownConnectorFactory factory = new WellKnownConnectorFactory(Set.of(low, high));

        RepositoryConnector connector = factory.newInstance(session(Map.of()), CENTRAL);

        assertThat(connector).isInstanceOf(WellKnownConnector.class);
        assertThat(high.created).isEqualTo(1);
        assertThat(low.created).isZero();
    }

    @Test
    void steps_aside_when_discovery_is_switched_off() {
        WellKnownConnectorFactory factory = new WellKnownConnectorFactory(Set.of(new RecordingFactory(0)));

        assertThatThrownBy(() -> factory.newInstance(session(Map.of("wellknown.discovery", "false")), CENTRAL))
                .isInstanceOf(NoRepositoryConnectorException.class)
                .hasMessageContaining("wellknown.discovery=false");
    }

    @Test
    void refuses_a_switch_that_is_no_flag() {
        WellKnownConnectorFactory factory = new WellKnownConnectorFactory(Set.of(new RecordingFactory(0)));

        assertThatThrownBy(() -> factory.newInstance(session(Map.of("wellknown.discovery", "ture")), CENTRAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-Dwellknown.discovery=ture is no flag");
    }

    private void template() {
        server.file("example.com",
                "maven=" + server.uri("/releases/v{version}/{artifactId}-{version}{-classifier}.{type}"),
                "maven.latest=" + server.uri("/releases/latest/{artifactId}.pom"),
                "maven.suffixes=none");
    }

    private WellKnownConnector connector() {
        return new WellKnownConnector(session(Map.of()), CENTRAL, delegate, server.discovery(), (session, repository) -> {
            roots.add(repository);
            return root;
        });
    }

    private ArtifactDownload download(String coordinate) {
        DefaultArtifact artifact = new DefaultArtifact(coordinate);
        return new ArtifactDownload(artifact, null, folder.resolve(artifact.toString().replace(':', '_')).toFile(), "warn");
    }

    private MetadataDownload metadataDownload(String groupId, String artifactId) {
        return new MetadataDownload(new DefaultMetadata(groupId, artifactId, "maven-metadata.xml",
                Metadata.Nature.RELEASE_OR_SNAPSHOT),
                null,
                folder.resolve(groupId + "/" + artifactId + "/maven-metadata-central.xml").toFile(),
                "warn");
    }

    private static RepositorySystemSession session(Map<String, String> properties) {
        return new DefaultRepositorySystemSession().setConfigProperties(properties);
    }

    private static byte[] metadata(String... versions) throws IOException {
        return new VersionListing("com.example", "lib", null, null, "20260101000000", List.of(versions))
                .filter(version -> true)
                .toBytes();
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static final class RecordingFactory implements RepositoryConnectorFactory {

        private final float priority;
        int created;

        RecordingFactory(float priority) {
            this.priority = priority;
        }

        @Override
        public RepositoryConnector newInstance(RepositorySystemSession session, RemoteRepository repository) {
            created++;
            return new RecordingConnector(repository);
        }

        @Override
        public float getPriority() {
            return priority;
        }
    }

    static final class RecordingConnector implements RepositoryConnector {

        private final RemoteRepository repository;
        final Map<String, byte[]> artifacts = new HashMap<>(), metadata = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        RecordingConnector(RemoteRepository repository) {
            this.repository = repository;
        }

        @Override
        public void get(Collection<? extends ArtifactDownload> artifactDownloads,
                        Collection<? extends MetadataDownload> metadataDownloads) {
            for (ArtifactDownload download : artifactDownloads == null ? List.<ArtifactDownload>of() : artifactDownloads) {
                asked.add(download.getArtifact().toString());
                byte[] bytes = artifacts.get(download.getArtifact().toString());
                if (bytes == null) {
                    download.setException(new ArtifactNotFoundException(download.getArtifact(), repository));
                } else {
                    write(download.getFile().toPath(), bytes);
                }
            }
            for (MetadataDownload download : metadataDownloads == null ? List.<MetadataDownload>of() : metadataDownloads) {
                String key = download.getMetadata().getGroupId() + ":" + download.getMetadata().getArtifactId();
                asked.add("metadata " + key);
                byte[] bytes = metadata.get(key);
                if (bytes == null) {
                    download.setException(new MetadataNotFoundException(download.getMetadata(), repository));
                } else {
                    write(download.getFile().toPath(), bytes);
                }
            }
        }

        @Override
        public void put(Collection<? extends ArtifactUpload> artifactUploads,
                        Collection<? extends MetadataUpload> metadataUploads) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        private static void write(Path file, byte[] bytes) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.write(file, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
