package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslationTlsConnectionTest {
    @TempDir
    static Path temporary;

    static Path trustStore;

    @BeforeAll
    static void certificate() throws Exception {
        trustStore = temporary.resolve("localhost.p12");
        var process = new ProcessBuilder(
                        executable("keytool"),
                        "-genkeypair",
                        "-alias",
                        "localhost",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=ip:127.0.0.1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        trustStore.toString(),
                        "-storepass",
                        "local-test-only",
                        "-keypass",
                        "local-test-only")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void healthyProviderCanCompleteASlowTlsHandshakeWithinTheAttemptDeadline() throws Exception {
        probe(2000, TextTranslator.Failure.NONE);
    }

    @Test
    void shortAttemptDeadlineReturnsBeforeTlsHandshakeCompletes() throws Exception {
        probe(80, TextTranslator.Failure.TEMPORARY);
    }

    private void probe(long timeoutMillis, TextTranslator.Failure expected) throws Exception {
        char[] password = "local-test-only".toCharArray();
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(trustStore)) {
            store.load(input, password);
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(HttpsParameters parameters) {
                try {
                    Thread.sleep(650);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                parameters.setSSLParameters(ssl.getDefaultSSLParameters());
            }
        });
        server.createContext("/language/translate/v2", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = "{\"data\":{\"translations\":[{\"translatedText\":\"Study\"}]}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        Process child = null;
        try {
            // A separate JVM trusts this local certificate without changing other tests' TLS state.
            child = new ProcessBuilder(
                            executable("java"),
                            "-Djavax.net.ssl.trustStore=" + trustStore,
                            "-Djavax.net.ssl.trustStorePassword=local-test-only",
                            "-cp",
                            classpath(),
                            Probe.class.getName(),
                            "https://127.0.0.1:" + server.getAddress().getPort(),
                            Long.toString(timeoutMillis),
                            expected.name())
                    .redirectErrorStream(true)
                    .start();
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).as(output).isZero();
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            server.stop(0);
        }
    }

    private static String executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static String classpath() throws Exception {
        var paths = new LinkedHashSet<String>();
        paths.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) paths.add(Path.of(url.toURI()).toString());
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    public static class Probe {
        public static void main(String[] args) {
            var translator = new GoogleTextTranslator(URI.create(args[0]), "fake-local-key");
            long started = System.nanoTime();
            var reply = translator.translate(
                    List.of("공부"), TranslationLanguage.EN, Duration.ofMillis(Long.parseLong(args[1])));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (!reply.failure().name().equals(args[2]))
                throw new AssertionError("Expected " + args[2] + " but received " + reply.failure());
            if (reply.failure() == TextTranslator.Failure.NONE && !reply.texts().equals(List.of("Study")))
                throw new AssertionError("Unexpected translated text");
            if (args[2].equals("TEMPORARY") && elapsedMillis >= 500)
                throw new AssertionError("Short deadline waited for the slow handshake: " + elapsedMillis + "ms");
        }
    }
}
