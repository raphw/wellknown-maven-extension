package codes.rafael.wellknown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryTest {

    private DiscoveryServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new DiscoveryServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void reverses_a_group_into_its_domain() {
        assertThat(Discovery.domain("net.bytebuddy.agent")).isEqualTo("agent.bytebuddy.net");
    }

    @Test
    void reads_the_file_of_the_shortest_domain_that_publishes_one() throws IOException {
        server.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/releases/");
        server.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/releases/");

        DiscoveredLocation location = server.discovery().lookup("net.bytebuddy.agent", "maven", "byte-buddy-agent")
                .orElseThrow();

        assertThat(location.domain()).isEqualTo("bytebuddy.net");
        assertThat(location.target()).isEqualTo("https://maven.bytebuddy.net/releases/");
        assertThat(server.requested())
                .as("the first file found speaks for every name below its domain")
                .containsExactly("GET /bytebuddy.net/java-repository.properties");
    }

    @Test
    void asks_a_subdomain_only_where_the_domain_publishes_no_file() throws IOException {
        server.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/releases/");

        DiscoveredLocation location = server.discovery().lookup("net.bytebuddy.agent", "maven", "byte-buddy-agent")
                .orElseThrow();

        assertThat(location.domain()).isEqualTo("agent.bytebuddy.net");
    }

    @Test
    void answers_nothing_for_a_key_the_first_file_does_not_hold() throws IOException {
        server.file("bytebuddy.net", "module=https://repo.bytebuddy.net/");
        server.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/releases/");

        assertThat(server.discovery().lookup("net.bytebuddy.agent", "maven", "byte-buddy-agent")).isEmpty();
    }

    @Test
    void lets_its_subdomains_answer_first_when_a_file_delegates() throws IOException {
        server.file("bytebuddy.net", "delegate=true", "maven=https://maven.bytebuddy.net/releases/");
        server.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/releases/");

        assertThat(server.discovery().lookup("net.bytebuddy.agent", "maven", "byte-buddy-agent").orElseThrow().domain())
                .isEqualTo("agent.bytebuddy.net");
        assertThat(server.discovery().lookup("net.bytebuddy.utility", "maven", "byte-buddy-utility").orElseThrow().domain())
                .as("a delegating file keeps its own entries for a subdomain without one")
                .isEqualTo("bytebuddy.net");
    }

    @Test
    void chooses_the_exact_name_over_the_longest_prefix_over_the_key_for_all() throws IOException {
        server.file("bytebuddy.net",
                "maven=https://all.bytebuddy.net/",
                "maven[byte-*]=https://byte.bytebuddy.net/",
                "maven[byte-buddy-*]=https://prefix.bytebuddy.net/",
                "maven[byte-buddy-agent]=https://exact.bytebuddy.net/");
        Discovery discovery = server.discovery();

        assertThat(discovery.lookup("net.bytebuddy", "maven", "byte-buddy-agent").orElseThrow().target())
                .isEqualTo("https://exact.bytebuddy.net/");
        assertThat(discovery.lookup("net.bytebuddy", "maven", "byte-buddy-dep").orElseThrow().target())
                .isEqualTo("https://prefix.bytebuddy.net/");
        assertThat(discovery.lookup("net.bytebuddy", "maven", "byte-buddy").orElseThrow().target())
                .isEqualTo("https://byte.bytebuddy.net/");
        assertThat(discovery.lookup("net.bytebuddy", "maven", "other").orElseThrow().target())
                .isEqualTo("https://all.bytebuddy.net/");
        assertThat(server.requested()).as("each domain is asked once").hasSize(1);
    }

    @Test
    void reads_the_keys_beside_a_selected_key_with_its_selector() throws IOException {
        server.file("jenesis.build",
                "maven[build.jenesis]=https://example.com/v{version}/{artifactId}-{version}{-classifier}.{type}",
                "maven[build.jenesis].since=0.15.0",
                "maven[build.jenesis].suffixes=none, RC",
                "maven[build.jenesis].latest=https://example.com/latest/{artifactId}.pom");

        DiscoveredLocation location = server.discovery().lookup("build.jenesis", "maven", "build.jenesis").orElseThrow();

        assertThat(location.since()).isEqualTo("0.15.0");
        assertThat(location.suffixes()).containsExactly("none", "rc");
        assertThat(location.latest()).isEqualTo("https://example.com/latest/{artifactId}.pom");
    }

    @Test
    void counts_a_file_that_cannot_be_fetched_as_absent() throws IOException {
        server.answer("/bytebuddy.net/java-repository.properties", 500, Map.of());
        server.file("agent.bytebuddy.net", "maven=https://agent.bytebuddy.net/releases/");

        assertThat(server.discovery().lookup("net.bytebuddy.agent", "maven", "byte-buddy-agent").orElseThrow().domain())
                .isEqualTo("agent.bytebuddy.net");
    }

    @Test
    void asks_nothing_for_a_name_that_is_no_domain() throws IOException {
        assertThat(server.discovery().lookup("junit", "maven", "junit")).isEmpty();
        assertThat(server.requested()).isEmpty();
    }

    @Test
    void remembers_the_answer_of_a_domain_for_its_time_to_live(@TempDir Path cache) throws IOException {
        server.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/releases/");
        Discovery discovery = new Discovery(server.location(),
                new WellKnownClient(new Configuration().location(server.location()).insecure(true)),
                Duration.ofSeconds(5),
                cache,
                Duration.ofHours(1));
        discovery.lookup("net.bytebuddy", "maven", "byte-buddy");
        discovery.lookup("com.example", "maven", "lib");
        Discovery remembered = new Discovery(server.location(),
                new WellKnownClient(new Configuration().location(server.location()).insecure(true)),
                Duration.ofSeconds(5),
                cache,
                Duration.ofHours(1));

        assertThat(remembered.lookup("net.bytebuddy", "maven", "byte-buddy").orElseThrow().target())
                .isEqualTo("https://maven.bytebuddy.net/releases/");
        assertThat(remembered.lookup("com.example", "maven", "lib")).isEmpty();
        assertThat(server.requested()).as("a later build asks neither domain again").hasSize(2);
        assertThat(cache.resolve("bytebuddy.net.properties")).exists();
        assertThat(cache.resolve("example.com.absent")).exists();
    }

    @Test
    void asks_again_once_the_answer_it_remembers_has_expired(@TempDir Path cache) throws IOException {
        server.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/releases/");
        new Discovery(server.location(),
                new WellKnownClient(new Configuration().location(server.location()).insecure(true)),
                Duration.ofSeconds(5),
                cache,
                Duration.ZERO).lookup("com.example", "maven", "lib");
        server.file("example.com", "maven=https://maven.example.com/");

        assertThat(new Discovery(server.location(),
                new WellKnownClient(new Configuration().location(server.location()).insecure(true)),
                Duration.ofSeconds(5),
                cache,
                Duration.ZERO).lookup("com.example", "maven", "lib")).isPresent();
        assertThat(cache.resolve("example.com.absent")).doesNotExist();
    }

    @Test
    void refuses_a_suffix_that_is_not_a_word() {
        server.file("bytebuddy.net", "maven=https://maven.bytebuddy.net/", "maven.suffixes=none,rc-1");

        assertThatThrownBy(() -> server.discovery().lookup("net.bytebuddy", "maven", "byte-buddy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suffix 'rc-1'");
    }

    @Test
    void refuses_a_key_without_a_value() {
        server.file("bytebuddy.net", "maven=");

        assertThatThrownBy(() -> server.discovery().lookup("net.bytebuddy", "maven", "byte-buddy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names maven without a value");
    }

    @Test
    void refuses_a_coordinate_where_a_location_is_expected() {
        server.file("bytebuddy.net", "maven=net.bytebuddy:byte-buddy");

        assertThatThrownBy(() -> server.discovery().lookup("net.bytebuddy", "maven", "byte-buddy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("where a location is expected");
    }

    @Test
    void refuses_a_latest_link_beside_a_root() {
        server.file("bytebuddy.net",
                "maven=https://maven.bytebuddy.net/",
                "maven.latest=https://maven.bytebuddy.net/latest");

        assertThatThrownBy(() -> server.discovery().lookup("net.bytebuddy", "maven", "byte-buddy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a template naming {version}");
    }

    @Test
    void refuses_a_delegate_that_is_no_flag() {
        server.file("bytebuddy.net", "delegate=yes", "maven=https://maven.bytebuddy.net/");

        assertThatThrownBy(() -> server.discovery().lookup("net.bytebuddy", "maven", "byte-buddy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegate=yes");
    }

    @Test
    void refuses_a_file_location_read_over_plain_http() {
        assertThatThrownBy(() -> new Discovery(server.location(), new WellKnownClient(new Configuration())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only read over https");
    }
}
