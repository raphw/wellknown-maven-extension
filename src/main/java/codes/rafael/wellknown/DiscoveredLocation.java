package codes.rafael.wellknown;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DiscoveredLocation(String domain,
                                 URI source,
                                 String key,
                                 String target,
                                 String since,
                                 List<String> suffixes,
                                 String latest) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    private static final String CLASSIFIER = "-classifier", TYPE = "type", METADATA = "/maven-metadata.xml";
    private static final List<Map.Entry<String, String>> CHECKSUMS = List.of(Map.entry("sha512", "SHA-512"),
            Map.entry("sha256", "SHA-256"),
            Map.entry("sha1", "SHA-1"));

    public boolean admits(String version) {
        if (version == null) {
            return since == null && suffixes == null;
        }
        if (since != null && new ComparableVersion(version).compareTo(new ComparableVersion(since)) < 0) {
            return false;
        }
        if (suffixes == null) {
            return true;
        }
        int dash = version.indexOf('-');
        if (dash < 0) {
            return suffixes.contains("none");
        }
        String qualifier = version.substring(dash + 1).toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (qualifier.startsWith(suffix) && (qualifier.length() == suffix.length()
                    || !Character.isLetter(qualifier.charAt(suffix.length())))) {
                return true;
            }
        }
        return false;
    }

    public boolean restricted() {
        return since != null || suffixes != null;
    }

    public boolean template() {
        return PLACEHOLDER.matcher(target).find();
    }

    public URI root(boolean insecure) {
        if (template()) {
            throw new IllegalStateException(key + " in " + source + " names a template, not a root: " + target);
        }
        return secured(URI.create(target.endsWith("/") ? target : target + "/"), insecure);
    }

    public Optional<URI> resolve(Map<String, String> values, boolean insecure) {
        return expand(values).map(text -> secured(URI.create(text), insecure));
    }

    public Optional<String> expand(Map<String, String> values) {
        Optional<String> expanded = fill(target, key, values);
        if (expanded.isPresent()
                && (!target.contains("{" + CLASSIFIER + "}") && values.get(CLASSIFIER) != null
                && !values.get(CLASSIFIER).isEmpty()
                || !target.contains("{" + TYPE + "}") && values.get(TYPE) != null && !values.get(TYPE).equals("jar"))) {
            return Optional.empty();
        }
        return expanded;
    }

    public boolean listsVersions() {
        return latest != null && latest.endsWith(METADATA);
    }

    public Optional<String> latest(Map<String, String> values, WellKnownClient client) throws IOException {
        if (latest == null) {
            return Optional.empty();
        }
        URI uri = secured(URI.create(fill(latest, key + ".latest", values).orElseThrow()), client.insecure());
        String version = listsVersions()
                ? metadata(values, client).map(VersionListing::release).orElse(null)
                : redirected(uri, values, client);
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(key + ".latest in " + source + " points to " + uri
                    + ", which names '" + version + "' where it should name the newest version, such as 1.2.3");
        }
        return Optional.ofNullable(version);
    }

    public Optional<VersionListing> metadata(Map<String, String> values, WellKnownClient client) throws IOException {
        if (!listsVersions()) {
            return Optional.empty();
        }
        Optional<byte[]> bytes = client.read(secured(URI.create(fill(latest, key + ".latest", values).orElseThrow()),
                client.insecure()));
        return bytes.isEmpty()
                ? Optional.empty()
                : Optional.of(VersionListing.parse(bytes.get()).filter(this::admits));
    }

    public Optional<byte[]> fetch(URI location, Map<String, String> values, WellKnownClient client) throws IOException {
        Optional<byte[]> bytes = client.read(location);
        if (bytes.isEmpty() || values.get(TYPE) == null || !target.contains("{" + TYPE + "}")) {
            return bytes;
        }
        for (Map.Entry<String, String> checksum : CHECKSUMS) {
            Map<String, String> sibling = new HashMap<>(values);
            sibling.put(TYPE, values.get(TYPE) + "." + checksum.getKey());
            Optional<byte[]> published = client.read(resolve(sibling, client.insecure()).orElseThrow());
            if (published.isEmpty()) {
                continue;
            }
            String expected = new String(published.get(), StandardCharsets.US_ASCII).strip().split("\\s+")[0];
            String actual;
            try {
                actual = HexFormat.of().formatHex(MessageDigest.getInstance(checksum.getValue()).digest(bytes.get()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("The JDK offers no " + checksum.getValue(), e);
            }
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("Failed checksum validation for " + location
                        + " against its " + checksum.getValue() + " checksum, named by " + key + " in " + source);
            }
            break;
        }
        return bytes;
    }

    private String redirected(URI uri, Map<String, String> values, WellKnownClient client) throws IOException {
        HttpResponse<Void> response = client.head(uri);
        int status = response.statusCode();
        if (status == 404 || status == 410) {
            return null;
        }
        Optional<String> announced = response.headers().firstValue("Jenesis-MavenVersion");
        if (announced.isPresent()) {
            return announced.get().strip();
        }
        Optional<String> redirect = response.headers().firstValue("Location");
        if (status / 100 != 3 || redirect.isEmpty()) {
            throw new IllegalArgumentException(key + ".latest in " + source + " points to " + uri
                    + ", which answered " + status + " without redirecting to the newest version or naming it");
        }
        String destination = uri.resolve(redirect.get()).toString();
        int marker = target.indexOf("{version}"), boundary = target.indexOf('/', marker);
        String shape = target.substring(0, boundary < 0 ? target.length() : boundary);
        StringBuilder pattern = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(shape);
        int last = 0;
        while (matcher.find()) {
            pattern.append(Pattern.quote(shape.substring(last, matcher.start())));
            String placeholder = matcher.group(1);
            if (placeholder.equals("version")) {
                pattern.append(matcher.start() == marker ? "(?<version>[A-Za-z0-9._+-]+)" : "\\k<version>");
            } else if (values.get(placeholder) != null) {
                pattern.append(Pattern.quote(values.get(placeholder)));
            } else if (placeholder.equals(CLASSIFIER) || placeholder.equals(TYPE)) {
                pattern.append("[^/?#]*");
            } else {
                throw new IllegalArgumentException(key + " in " + source + " uses the placeholder {"
                        + placeholder + "}, which a request without a version cannot fill");
            }
            last = matcher.end();
        }
        pattern.append(Pattern.quote(shape.substring(last))).append("(?:[/?#].*)?");
        Matcher matched = Pattern.compile(pattern.toString()).matcher(destination);
        if (!matched.matches()) {
            throw new IllegalArgumentException(key + ".latest in " + source + " points to " + uri
                    + ", which leads to " + destination + " where " + key + " expects " + shape);
        }
        return matched.group("version");
    }

    private Optional<String> fill(String template, String name, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (!values.containsKey(placeholder)) {
                throw new IllegalArgumentException(name + " in " + source + " uses the placeholder {"
                        + placeholder + "}, but " + name + " knows only "
                        + values.keySet().stream().sorted().map(known -> "{" + known + "}").toList());
            }
            if (values.get(placeholder) == null) {
                return Optional.empty();
            }
        }
        return Optional.of(matcher.reset().replaceAll(result -> Matcher.quoteReplacement(values.get(result.group(1)))));
    }

    private URI secured(URI uri, boolean insecure) {
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && insecure)) {
            throw new IllegalStateException(key + " in " + source + " points to " + uri
                    + ", but a location a domain names is only read over https"
                    + " (set -Dwellknown.insecure=true to allow plaintext http)");
        }
        return uri;
    }
}
