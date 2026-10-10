package codes.rafael.wellknown;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryTlsTest {

    @TempDir
    Path folder;

    @Test
    void counts_a_domain_that_resets_the_handshake_as_publishing_nothing() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoLinger(true, 0);
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
            String location = "https://localhost:" + server.getLocalPort() + "/{domain}/java-repository.properties";

            assertThat(new Discovery(location, new WellKnownClient(new Configuration().location(location)))
                    .lookup("com.example", "maven", "lib")).isEmpty();
        }
    }

    @Test
    void fails_on_a_domain_whose_certificate_does_not_verify() throws Exception {
        Path keystore = folder.resolve("keystore.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "secret",
                "-keyalg", "EC", "-alias", "localhost", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost")
                .redirectErrorStream(true)
                .start();
        try (InputStream inputStream = process.getInputStream()) {
            inputStream.transferTo(OutputStream.nullOutputStream());
        }
        assertThat(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0).isTrue();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = Files.newInputStream(keystore)) {
            store.load(inputStream, "secret".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "secret".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String location = "https://localhost:" + server.getAddress().getPort() + "/{domain}/java-repository.properties";

            assertThatThrownBy(() -> new Discovery(location, new WellKnownClient(new Configuration().location(location)))
                    .lookup("com.example", "maven", "lib"))
                    .isInstanceOf(IOException.class);
        } finally {
            server.stop(0);
        }
    }
}
