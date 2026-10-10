package codes.rafael.wellknown;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveredLocationTest {

    private static final String TEMPLATE = "https://example.com/v{version}/{artifactId}-{version}{-classifier}.{type}";

    @Test
    void admits_versions_since_the_first_one_named_as_maven_orders_them() {
        DiscoveredLocation location = location(TEMPLATE, "1.2.3", null);

        assertThat(location.admits("1.2.3")).isTrue();
        assertThat(location.admits("1.10.0")).isTrue();
        assertThat(location.admits("1.2.3-rc1")).isFalse();
        assertThat(location.admits("1.2")).isFalse();
    }

    @Test
    void admits_the_qualifiers_its_suffixes_name() {
        DiscoveredLocation location = location(TEMPLATE, null, List.of("none", "rc"));

        assertThat(location.admits("1.2.3")).isTrue();
        assertThat(location.admits("1.2.3-RC2")).isTrue();
        assertThat(location.admits("1.2.3-rc.1")).isTrue();
        assertThat(location.admits("1.2.3-SNAPSHOT")).isFalse();
        assertThat(location.admits("1.2.3-rcx")).as("a suffix is a whole leading word").isFalse();
    }

    @Test
    void expands_a_template_with_the_coordinate_it_is_asked_for() {
        assertThat(location(TEMPLATE, null, null).expand(values("1.0", "-sources", "jar")))
                .contains("https://example.com/v1.0/lib-1.0-sources.jar");
    }

    @Test
    void serves_only_the_plain_jar_from_a_template_without_classifier_or_type() {
        DiscoveredLocation location = location("https://example.com/{version}/lib.jar", null, null);

        assertThat(location.expand(values("1.0", "", "jar"))).contains("https://example.com/1.0/lib.jar");
        assertThat(location.expand(values("1.0", "-sources", "jar"))).isEmpty();
        assertThat(location.expand(values("1.0", "", "pom"))).isEmpty();
    }

    @Test
    void refuses_a_placeholder_maven_does_not_know() {
        assertThatThrownBy(() -> location("https://example.com/{module}.jar", null, null).expand(values("1.0", "", "jar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{module}");
    }

    @Test
    void refuses_a_location_not_read_over_https() {
        DiscoveredLocation location = location("http://example.com/releases", null, null);

        assertThatThrownBy(() -> location.root(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only read over https");
        assertThat(location.root(true)).isEqualTo(URI.create("http://example.com/releases/"));
    }

    private static DiscoveredLocation location(String target, String since, List<String> suffixes) {
        return new DiscoveredLocation("example.com",
                URI.create("https://example.com/.well-known/java-repository.properties"),
                "maven",
                target,
                since,
                suffixes,
                null);
    }

    private static Map<String, String> values(String version, String classifier, String type) {
        return Map.of("groupId", "com.example",
                "groupPath", "com/example",
                "artifactId", "lib",
                "version", version,
                "-classifier", classifier,
                "type", type);
    }
}
