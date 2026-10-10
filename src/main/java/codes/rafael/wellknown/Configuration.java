package codes.rafael.wellknown;

import java.util.Map;

public record Configuration(boolean discovery,
                            String location,
                            boolean insecure,
                            int timeout,
                            int ttl,
                            int connectTimeout,
                            int requestTimeout) {

    private static final String LOCATION = "https://{domain}/.well-known/java-repository.properties";

    public Configuration {
        if (!location.contains("{domain}")) {
            throw new IllegalArgumentException("The location of a domain's file must name {domain}: " + location);
        }
        if (timeout < 0 || ttl < 0 || connectTimeout < 0 || requestTimeout < 0) {
            throw new IllegalArgumentException("A timeout or a time to live is a number of at least 0, not "
                    + Math.min(Math.min(timeout, ttl), Math.min(connectTimeout, requestTimeout)));
        }
    }

    public Configuration() {
        this(true, LOCATION, false, 5_000, 86_400, 10_000, 1_800_000);
    }

    public static Configuration ofProperties(Map<?, ?> properties) {
        return new Configuration(flag(properties, "wellknown.discovery", true),
                value(properties, "wellknown.location", LOCATION),
                flag(properties, "wellknown.insecure", false),
                number(properties, "wellknown.timeout", 5_000),
                number(properties, "wellknown.ttl", 86_400),
                number(properties, "aether.connector.connectTimeout", 10_000),
                number(properties, "aether.connector.requestTimeout", 1_800_000));
    }

    public Configuration discovery(boolean discovery) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration location(String location) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration insecure(boolean insecure) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration timeout(int timeout) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration ttl(int ttl) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration connectTimeout(int connectTimeout) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    public Configuration requestTimeout(int requestTimeout) {
        return new Configuration(discovery, location, insecure, timeout, ttl, connectTimeout, requestTimeout);
    }

    private static String value(Map<?, ?> properties, String key, String fallback) {
        Object value = properties.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().strip();
    }

    private static boolean flag(Map<?, ?> properties, String key, boolean fallback) {
        Object value = properties.get(key);
        if (value == null) {
            return fallback;
        }
        return switch (value.toString().strip()) {
            case "", "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("-D" + key + "=" + value
                    + " is no flag, where true, false or the property without a value are valid");
        };
    }

    private static int number(Map<?, ?> properties, String key, int fallback) {
        Object value = properties.get(key);
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-D" + key + "=" + value + " is no number", e);
        }
    }
}
