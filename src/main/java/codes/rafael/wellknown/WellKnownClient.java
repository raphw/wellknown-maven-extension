package codes.rafael.wellknown;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class WellKnownClient {

    private static final String USER_AGENT = "wellknown-maven-extension";

    private final HttpClient following, direct;
    private final Duration requestTimeout;
    private final boolean insecure;

    public WellKnownClient(Configuration configuration) {
        this(configuration, ProxySelector.getDefault(), null);
    }

    public WellKnownClient(Configuration configuration, ProxySelector proxies, Authenticator authenticator) {
        following = client(configuration, proxies, authenticator, HttpClient.Redirect.NORMAL);
        direct = client(configuration, proxies, authenticator, HttpClient.Redirect.NEVER);
        requestTimeout = configuration.requestTimeout() == 0 ? null : Duration.ofMillis(configuration.requestTimeout());
        insecure = configuration.insecure();
    }

    public static WellKnownClient ofSession(RepositorySystemSession session, Configuration configuration) {
        ProxySelector fallback = ProxySelector.getDefault();
        ProxySelector proxies = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                org.eclipse.aether.repository.Proxy proxy = proxy(session, uri);
                if (proxy != null) {
                    return List.of(new Proxy(Proxy.Type.HTTP,
                            InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
                }
                return fallback == null ? List.of(Proxy.NO_PROXY) : fallback.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                if (fallback != null) {
                    fallback.connectFailed(uri, address, exception);
                }
            }
        };
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                URI uri = URI.create(getRequestingURL().toString());
                org.eclipse.aether.repository.Proxy proxy = proxy(session, uri);
                if (proxy == null) {
                    return null;
                }
                RemoteRepository repository = new RemoteRepository.Builder(repository(uri)).setProxy(proxy).build();
                try (AuthenticationContext context = AuthenticationContext.forProxy(session, repository)) {
                    if (context == null || context.get(AuthenticationContext.USERNAME) == null) {
                        return null;
                    }
                    String password = context.get(AuthenticationContext.PASSWORD);
                    return new PasswordAuthentication(context.get(AuthenticationContext.USERNAME),
                            password == null ? new char[0] : password.toCharArray());
                }
            }
        };
        return new WellKnownClient(configuration, proxies, authenticator);
    }

    public boolean insecure() {
        return insecure;
    }

    public Optional<byte[]> read(URI uri) throws IOException {
        return read(uri, requestTimeout);
    }

    public Optional<byte[]> read(URI uri, Duration timeout) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (timeout != null) {
            request.timeout(timeout);
        }
        HttpResponse<byte[]> response = send(following, request, HttpResponse.BodyHandlers.ofByteArray());
        return switch (response.statusCode()) {
            case 200 -> Optional.of(response.body());
            case 404, 410 -> Optional.empty();
            default -> throw new IOException(uri + " answered " + response.statusCode());
        };
    }

    public boolean exists(URI uri) throws IOException {
        HttpResponse<Void> response = send(following,
                HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.discarding());
        return switch (response.statusCode()) {
            case 200 -> true;
            case 404, 410 -> false;
            default -> throw new IOException(uri + " answered " + response.statusCode());
        };
    }

    public HttpResponse<Void> head(URI uri) throws IOException {
        return send(direct,
                HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.discarding());
    }

    private <T> HttpResponse<T> send(HttpClient client,
                                     HttpRequest.Builder request,
                                     HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            if (requestTimeout != null && request.build().timeout().isEmpty()) {
                request.timeout(requestTimeout);
            }
            HttpResponse<T> response = client.send(request.header("User-Agent", USER_AGENT).build(), handler);
            if (!"https".equals(response.uri().getScheme()) && !insecure) {
                throw new IOException(request.build().uri() + " was redirected to " + response.uri()
                        + ", but a location a domain names is only read over https");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading " + request.build().uri());
        }
    }

    private static HttpClient client(Configuration configuration,
                                     ProxySelector proxies,
                                     Authenticator authenticator,
                                     HttpClient.Redirect redirect) {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(redirect);
        if (configuration.connectTimeout() > 0) {
            builder.connectTimeout(Duration.ofMillis(configuration.connectTimeout()));
        }
        if (proxies != null) {
            builder.proxy(proxies);
        }
        if (authenticator != null) {
            builder.authenticator(authenticator);
        }
        return builder.build();
    }

    private static org.eclipse.aether.repository.Proxy proxy(RepositorySystemSession session, URI uri) {
        return session.getProxySelector() == null ? null : session.getProxySelector().getProxy(repository(uri));
    }

    private static RemoteRepository repository(URI uri) {
        return new RemoteRepository.Builder("wellknown-" + uri.getHost(),
                "default",
                uri.getScheme() + "://" + uri.getRawAuthority() + "/").build();
    }
}
