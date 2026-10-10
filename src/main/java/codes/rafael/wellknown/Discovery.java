package codes.rafael.wellknown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Discovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(Discovery.class);

    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,63}(\\.[A-Za-z0-9_-]{1,63})+");
    private static final Pattern SELECTED = Pattern.compile("([a-z]+)\\[([^\\]]*)]");
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9_.-]+\\*?");

    private final String location;
    private final WellKnownClient client;
    private final Duration timeout;
    private final Path cache;
    private final Duration ttl;
    private final Map<String, FutureTask<Optional<Properties>>> files = new ConcurrentHashMap<>();

    public Discovery(String location, WellKnownClient client) {
        this(location, client, Duration.ofSeconds(5), null, Duration.ZERO);
    }

    public Discovery(String location, WellKnownClient client, Duration timeout, Path cache, Duration ttl) {
        if (!location.contains("{domain}")) {
            throw new IllegalArgumentException("The location of a domain's file must name {domain}: " + location);
        }
        if (!location.startsWith("https://") && !(location.startsWith("http://") && client.insecure())) {
            throw new IllegalArgumentException("The location of a domain's file is only read over https: " + location
                    + " (set -Dwellknown.insecure=true to allow plaintext http)");
        }
        this.location = location;
        this.client = client;
        this.timeout = timeout;
        this.cache = cache;
        this.ttl = ttl;
    }

    public static String domain(String namespace) {
        List<String> labels = Arrays.asList(namespace.split("\\."));
        Collections.reverse(labels);
        return String.join(".", labels);
    }

    public WellKnownClient client() {
        return client;
    }

    public Optional<DiscoveredLocation> lookup(String namespace, String kind, String name) throws IOException {
        if (!NAME.matcher(namespace).matches()) {
            return Optional.empty();
        }
        String[] labels = namespace.split("\\.");
        DiscoveredLocation found = null;
        for (int count = 2; count <= labels.length; count++) {
            String domain = domain(String.join(".", Arrays.copyOf(labels, count)));
            URI source = URI.create(location.replace("{domain}", domain));
            Optional<Properties> file = file(domain, source);
            if (file.isEmpty()) {
                continue;
            }
            Properties properties = file.get();
            String delegate = value(properties, source, "delegate");
            if (delegate != null && !delegate.equals("true") && !delegate.equals("false")) {
                throw new IllegalArgumentException(source + " names delegate=" + delegate + ", where it expects false,"
                        + " the default, to answer alone for every name below " + domain + ", or true to let the files"
                        + " of its subdomains answer first");
            }
            String key = properties.containsKey(kind) ? kind : null;
            int matched = -1;
            for (String property : properties.stringPropertyNames()) {
                Matcher matcher = SELECTED.matcher(property);
                if (!matcher.matches() || !matcher.group(1).equals(kind)) {
                    continue;
                }
                String selector = matcher.group(2);
                if (!SELECTOR.matcher(selector).matches()) {
                    throw new IllegalArgumentException(source + " names " + property + ", where a selector is an"
                            + " artifact ID, such as " + kind + "[byte-buddy], or the start of one followed by *,"
                            + " such as " + kind + "[byte-buddy-*]");
                }
                int score = selector.endsWith("*")
                        ? (name.startsWith(selector.substring(0, selector.length() - 1)) ? selector.length() - 1 : -1)
                        : (name.equals(selector) ? Integer.MAX_VALUE : -1);
                if (score > matched) {
                    key = property;
                    matched = score;
                }
            }
            if (key != null) {
                String suffixes = value(properties, source, key + ".suffixes");
                List<String> admitted = null;
                if (suffixes != null) {
                    admitted = new ArrayList<>();
                    for (String suffix : suffixes.split(",", -1)) {
                        String word = suffix.strip();
                        if (!SUFFIX.matcher(word).matches()) {
                            throw new IllegalArgumentException(source + " lists the suffix '" + word + "' in " + key
                                    + ".suffixes, where a suffix is a word of letters and digits, such as SNAPSHOT"
                                    + " or rc, or none for a version without one");
                        }
                        admitted.add(word.toLowerCase(Locale.ROOT));
                    }
                }
                DiscoveredLocation discovered = new DiscoveredLocation(domain,
                        source,
                        key,
                        value(properties, source, key),
                        value(properties, source, key + ".since"),
                        admitted == null ? null : List.copyOf(admitted),
                        value(properties, source, key + ".latest"));
                if (!discovered.target().contains("://")) {
                    throw new IllegalArgumentException(source + " names " + key + "=" + discovered.target()
                            + ", where a location is expected, either a Maven repository such as"
                            + " https://maven.example.com/releases/ or a template such as"
                            + " https://example.com/download/{version}/{artifactId}-{version}{-classifier}.{type}");
                }
                if (discovered.latest() != null && !discovered.target().contains("{version}")) {
                    throw new IllegalArgumentException(source + " names " + key + ".latest beside "
                            + discovered.target() + ", but only a template naming {version} needs to be told the"
                            + " newest version, as a root lists its versions itself");
                }
                found = discovered;
            }
            if (!"true".equals(delegate)) {
                break;
            }
        }
        return Optional.ofNullable(found);
    }

    private Optional<Properties> file(String domain, URI source) throws IOException {
        FutureTask<Optional<Properties>> task = new FutureTask<>(() -> {
            byte[] bytes = read(domain, source);
            if (bytes == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            return Optional.of(properties);
        });
        FutureTask<Optional<Properties>> existing = files.putIfAbsent(domain, task);
        if (existing == null) {
            task.run();
            existing = task;
        }
        try {
            return existing.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading " + source);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException exception) {
                throw exception;
            } else if (e.getCause() instanceof RuntimeException exception) {
                throw exception;
            } else if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private byte[] read(String domain, URI source) throws IOException {
        Path file = cache == null ? null : cache.resolve(domain + ".properties"),
                absent = cache == null ? null : cache.resolve(domain + ".absent");
        if (cache != null && fresh(file)) {
            return Files.readAllBytes(file);
        } else if (cache != null && fresh(absent)) {
            return null;
        }
        byte[] bytes;
        long started = System.nanoTime();
        try {
            bytes = client.read(source, timeout).orElse(null);
            LOGGER.debug("{} {} after {} ms", bytes == null ? "No file at" : "Read", source,
                    (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            LOGGER.debug("Could not read {} after {} ms: {}", source, (System.nanoTime() - started) / 1_000_000, e.toString());
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof CertificateException || cause instanceof SSLPeerUnverifiedException) {
                    throw e;
                }
            }
            bytes = null;
        }
        if (cache != null) {
            try {
                Files.createDirectories(cache);
                Path written = bytes == null ? absent : file;
                Path temporary = Files.createTempFile(cache, domain, ".tmp");
                try {
                    Files.write(temporary, bytes == null ? new byte[0] : bytes);
                    Files.move(temporary, written, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                Files.deleteIfExists(bytes == null ? file : absent);
            } catch (IOException e) {
                LOGGER.debug("Could not remember the answer of {} in {}: {}", source, cache, e.toString());
            }
        }
        return bytes;
    }

    private boolean fresh(Path file) throws IOException {
        return Files.isRegularFile(file)
                && Files.getLastModifiedTime(file).toInstant().plus(ttl).isAfter(Instant.now());
    }

    private static String value(Properties properties, URI source, String key) {
        String value = properties.getProperty(key);
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(source + " names " + key + " without a value");
        }
        return value == null ? null : value.strip();
    }
}
