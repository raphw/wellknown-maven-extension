package codes.rafael.wellknown;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class DiscoveryServer implements AutoCloseable {

    record Response(int status, Map<String, String> headers, byte[] body) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();

    DiscoveryServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(exchange.getRequestMethod() + " " + path);
            Response response = responses.getOrDefault(path, new Response(404, Map.of(), new byte[0]));
            response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(response.status(), head || response.body().length == 0
                    ? -1
                    : response.body().length);
            if (!head) {
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response.body());
                }
            }
            exchange.close();
        });
        server.start();
    }

    String uri(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    String location() {
        return "http://localhost:" + server.getAddress().getPort() + "/{domain}/java-repository.properties";
    }

    DiscoveryServer file(String domain, String... lines) {
        return serve("/" + domain + "/java-repository.properties", String.join("\n", lines));
    }

    DiscoveryServer serve(String path, String body) {
        return serve(path, body.getBytes(StandardCharsets.UTF_8));
    }

    DiscoveryServer serve(String path, byte[] body) {
        responses.put(path, new Response(200, Map.of(), body));
        return this;
    }

    DiscoveryServer answer(String path, int status, Map<String, String> headers) {
        responses.put(path, new Response(status, headers, new byte[0]));
        return this;
    }

    List<String> requested() {
        return List.copyOf(requested);
    }

    Discovery discovery() {
        return new Discovery(location(), new WellKnownClient(new Configuration().location(location()).insecure(true)));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
