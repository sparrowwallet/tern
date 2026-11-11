package com.sparrowwallet.tern.http.client;

import io.reactivex.plugins.RxJavaPlugins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class HttpEndpointClientTest {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String STORE_PASSWORD = "changeit";
    //Returned by a handler to reset the connection rather than close it
    private static final String RESET = new String("RESET");

    @TempDir
    private Path tempDir;

    private final List<Closeable> resources = new ArrayList<>();

    @AfterEach
    public void tearDown() throws IOException {
        for(Closeable resource : resources) {
            resource.close();
        }
    }

    @Test
    public void connectionIsReusedBetweenRequests() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{}")));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        for(int i = 0; i < 3; i++) {
            assertEquals(200, send(client, "http://127.0.0.1:" + server.getPort() + "/").statusCode());
        }
        assertEquals(1, server.connections.get());
    }

    @Test
    public void socks5ProxyIsGivenTheHostnameAndConnectionIsReused() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));
        HttpEndpointClient client = track(new HttpEndpointClient("test", new HttpProxy(HttpProxyProtocol.SOCKS5, "127.0.0.1", proxy.getPort()), null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        for(int i = 0; i < 3; i++) {
            assertEquals(200, send(client, "http://localhost:8332/").statusCode());
        }
        assertEquals(List.of("SOCKS5 DOMAIN:localhost:8332"), proxy.connectTargets);
    }

    @Test
    public void socks4ProxyIsGivenTheHostname() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));
        HttpEndpointClient client = track(new HttpEndpointClient("test", new HttpProxy(HttpProxyProtocol.SOCKS, "127.0.0.1", proxy.getPort()), null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertEquals(200, send(client, "http://localhost:8332/").statusCode());
        assertEquals(List.of("SOCKS4A DOMAIN:localhost:8332"), proxy.connectTargets);
    }

    @Test
    public void httpClientServiceSocksProxyIsGivenTheHostname() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{\"value\":1}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));
        HttpClientService service = new HttpClientService("127.0.0.1", proxy.getPort());
        try {
            Map<?, ?> result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.requestJson("http://localhost:8332/", Map.class, null));
            assertEquals(1, result.get("value"));
            assertEquals(List.of("SOCKS4A DOMAIN:localhost:8332"), proxy.connectTargets);
        } finally {
            service.stop();
        }
    }

    @Test
    public void httpClientServiceConcurrentFirstRequestsSucceed() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{\"value\":1}")));
        for(int iteration = 0; iteration < 10; iteration++) {
            HttpClientService service = new HttpClientService();
            try {
                int threads = 8;
                CountDownLatch ready = new CountDownLatch(threads);
                List<Callable<Map<?, ?>>> requests = new ArrayList<>();
                for(int i = 0; i < threads; i++) {
                    requests.add(() -> {
                        ready.countDown();
                        ready.await();
                        return service.requestJson("http://127.0.0.1:" + server.getPort() + "/", Map.class, null);
                    });
                }
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<Map<?, ?>>> results = executor.invokeAll(requests, 10, TimeUnit.SECONDS);
                    for(Future<Map<?, ?>> result : results) {
                        assertEquals(1, result.get().get("value"));
                    }
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                service.stop();
            }
        }
    }

    @Test
    public void httpClientServiceClientObtainedBeforeStopIsNotRestarted() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> ok("{\"value\":1}")));
        String url = "http://127.0.0.1:" + server.getPort() + "/";
        HttpClientService service = new HttpClientService();
        try {
            JettyHttpClient client = service.getHttpClient(HttpUsage.DEFAULT);
            assertEquals(1, assertTimeoutPreemptively(Duration.ofSeconds(10), () -> client.getJson(url, Map.class, null)).get("value"));

            service.stop();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(HttpSystemException.class, () -> client.getJson(url, Map.class, null)));
            assertEquals(1, server.requests.get());

            assertEquals(1, assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.requestJson(url, Map.class, null)).get("value"));
            assertEquals(2, server.requests.get());
        } finally {
            service.stop();
        }
    }

    @Test
    public void httpClientServiceRequestAbortedByStopIsNotANetworkError() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> {
            sleep(3000);
            return ok("{\"value\":1}");
        }));
        AtomicInteger identityChanges = new AtomicInteger();
        HttpClientService service = new HttpClientService(new IHttpProxySupplier() {
            @Override
            public Optional<HttpProxy> getHttpProxy(HttpUsage httpUsage) {
                return Optional.empty();
            }

            @Override
            public void changeIdentity() {
                identityChanges.incrementAndGet();
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<?, ?>> request = executor.submit(() -> service.requestJson("http://127.0.0.1:" + server.getPort() + "/", Map.class, null));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while(server.requests.get() == 0) {
                    Thread.sleep(10);
                }
            });

            service.stop();
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> request.get(10, TimeUnit.SECONDS));
            assertInstanceOf(HttpSystemException.class, thrown.getCause());
            assertEquals(1, server.requests.get());
            assertEquals(0, identityChanges.get());
        } finally {
            executor.shutdownNow();
            service.stop();
        }
    }

    @Test
    public void httpClientServiceInterruptedRequestIsNotRetriedOrReported() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> {
            sleep(3000);
            return ok("{\"value\":1}");
        }));
        AtomicInteger identityChanges = new AtomicInteger();
        HttpClientService service = new HttpClientService(new IHttpProxySupplier() {
            @Override
            public Optional<HttpProxy> getHttpProxy(HttpUsage httpUsage) {
                return Optional.empty();
            }

            @Override
            public void changeIdentity() {
                identityChanges.incrementAndGet();
            }
        });
        List<Throwable> undelivered = new CopyOnWriteArrayList<>();
        RxJavaPlugins.setErrorHandler(undelivered::add);
        try {
            //As a cancelled service interrupts the thread waiting for its request
            Thread caller = new Thread(() -> {
                try {
                    service.requestJson("http://127.0.0.1:" + server.getPort() + "/", Map.class, null);
                } catch(Exception e) {
                    //Expected on interruption
                }
            });
            caller.start();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while(server.requests.get() == 0) {
                    Thread.sleep(10);
                }
            });

            caller.interrupt();
            caller.join(10000);
            assertFalse(caller.isAlive());
            //Allows the abandoned request time to fail, or to be sent again
            Thread.sleep(1000);

            assertEquals(1, server.requests.get());
            assertEquals(0, identityChanges.get());
            assertTrue(undelivered.isEmpty(), undelivered.toString());
        } finally {
            RxJavaPlugins.reset();
            service.stop();
        }
    }

    @Test
    public void httpClientServiceUsageWithoutRedirectsReturnsTheRedirect() throws Exception {
        HttpServer target = track(new HttpServer(null, request -> ok("followed")));
        HttpServer server = track(new HttpServer(null, request -> "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:" + target.getPort() + "/\r\nContent-Length: 0\r\n\r\n"));
        HttpClientService service = new HttpClientService();
        try {
            HttpResponseException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(HttpResponseException.class,
                    () -> service.postString(new HttpUsage("NoRedirects", false), "http://127.0.0.1:" + server.getPort() + "/", null, "text/plain", "body")));
            assertEquals(307, thrown.getStatusCode());
            assertEquals(0, target.connections.get());
        } finally {
            service.stop();
        }
    }

    @Test
    public void httpClientServiceDefaultUsageFollowsRedirects() throws Exception {
        List<String> targetRequests = new CopyOnWriteArrayList<>();
        HttpServer target = track(new HttpServer(null, request -> {
            targetRequests.add(request);
            return ok("followed");
        }));
        HttpServer server = track(new HttpServer(null, request -> "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:" + target.getPort() + "/\r\nContent-Length: 0\r\n\r\n"));
        HttpClientService service = new HttpClientService();
        try {
            String response = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.postString("http://127.0.0.1:" + server.getPort() + "/", null, "text/plain", "body"));
            assertEquals("followed", response);
            assertTrue(targetRequests.getFirst().endsWith("\r\n\r\nbody"), targetRequests.getFirst());
        } finally {
            service.stop();
        }
    }

    @Test
    public void requestIsSentAndErrorResponseIsReturnedWithBody() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = track(new HttpServer(null, request -> {
            requests.add(request);
            return response(404, "{\"error\":\"not found\"}", "");
        }));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        HttpEndpointClient.Response response = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> client.send("POST", new URI("http://127.0.0.1:" + server.getPort() + "/wallet/test"),
                Map.of("Authorization", "Basic dXNlcjpwYXNz"), "application/json", "{\"method\":\"test\"}".getBytes(StandardCharsets.UTF_8), READ_TIMEOUT));
        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"not found\"}", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(0, response.serverCertificates().length);
        String request = requests.getFirst();
        assertTrue(request.startsWith("POST /wallet/test HTTP/1.1\r\n"), request);
        assertTrue(request.contains("\r\nAuthorization: Basic dXNlcjpwYXNz\r\n"), request);
        assertTrue(request.contains("\r\nContent-Type: application/json\r\n"), request);
        assertFalse(request.contains("User-Agent"), request);
        assertTrue(request.endsWith("\r\n\r\n{\"method\":\"test\"}"), request);
    }

    @Test
    public void authenticationChallengeIsReturned() throws Exception {
        for(String header : List.of("", "WWW-Authenticate: Basic realm=\"jsonrpc\"\r\n")) {
            HttpServer server = track(new HttpServer(null, request -> response(401, "", header)));
            HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
            assertEquals(401, send(client, "http://127.0.0.1:" + server.getPort() + "/").statusCode());
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    public void truncatedResponseIsNotReportedAsUnanswered() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{}"));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        IOException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class,
                () -> client.send("GET", new URI("http://127.0.0.1:" + server.getPort() + "/"), null, null, null, READ_TIMEOUT)));
        assertFalse(thrown instanceof EOFException, thrown.toString());
    }

    @Test
    public void redirectIsNotFollowed() throws Exception {
        HttpServer target = track(new HttpServer(null, request -> ok("{}")));
        for(String status : List.of("302 Found", "305 Use Proxy")) {
            HttpServer server = track(new HttpServer(null, request -> "HTTP/1.1 " + status + "\r\nLocation: http://127.0.0.1:" + target.getPort() + "/\r\nContent-Length: 0\r\n\r\n"));
            HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
            assertEquals(Integer.parseInt(status.substring(0, 3)), send(client, "http://127.0.0.1:" + server.getPort() + "/").statusCode());
        }
        assertEquals(0, target.connections.get());
    }

    @Test
    public void largeResponseIsReturned() throws Exception {
        String body = "x".repeat(5 * 1024 * 1024);
        HttpServer server = track(new HttpServer(null, request -> ok(body)));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertEquals(body.length(), send(client, "http://127.0.0.1:" + server.getPort() + "/").body().length);
    }

    @Test
    public void readTimeoutExpires() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> {
            sleep(5000);
            return ok("{}");
        }));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(SocketTimeoutException.class,
                () -> client.send("GET", new URI("http://127.0.0.1:" + server.getPort() + "/"), null, null, null, Duration.ofMillis(300))));
    }

    @Test
    public void zeroReadTimeoutOutlastsTheIdleTimeout() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> {
            sleep(1500);
            return ok("{}");
        }));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, Duration.ofMillis(300)));
        HttpEndpointClient.Response response = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> client.send("GET", new URI("http://127.0.0.1:" + server.getPort() + "/"), null, null, null, Duration.ZERO));
        assertEquals(200, response.statusCode());
    }

    @Test
    public void closedWithoutResponseThrowsEofException() throws Exception {
        HttpServer server = track(new HttpServer(null, request -> null));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(EOFException.class,
                () -> client.send("POST", new URI("http://127.0.0.1:" + server.getPort() + "/"), null, "application/json", "{}".getBytes(StandardCharsets.UTF_8), READ_TIMEOUT)));
        assertEquals(1, server.connections.get());
    }

    @Test
    public void slowProxyConnectionIsAllowedTheConnectTimeout() throws Exception {
        assertSlowProxyConnectionIsAllowedTheConnectTimeout(null);
    }

    @Test
    public void slowProxyConnectionOverTlsIsAllowedTheConnectTimeout() throws Exception {
        assertSlowProxyConnectionIsAllowedTheConnectTimeout(createKeyStore("slow"));
    }

    private void assertSlowProxyConnectionIsAllowedTheConnectTimeout(KeyStore keyStore) throws Exception {
        HttpServer server = track(new HttpServer(keyStore == null ? null : createServerContext(keyStore), request -> ok("{}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));
        proxy.connectDelayMillis = 1500;
        SSLContext clientContext = keyStore == null ? null : createClientContext((X509Certificate)keyStore.getCertificate(keyStore.aliases().nextElement()));
        HttpEndpointClient client = track(new HttpEndpointClient("test", new HttpProxy(HttpProxyProtocol.SOCKS5, "127.0.0.1", proxy.getPort()), clientContext,
                Duration.ofSeconds(5), Duration.ofMillis(500)));
        assertEquals(200, send(client, (keyStore == null ? "http" : "https") + "://slownode.onion:8332/").statusCode());
    }

    @Test
    public void unusedConnectionIsClosedAfterTheIdleTimeout() throws Exception {
        assertUnusedConnectionIsClosedAfterTheIdleTimeout(null);
    }

    @Test
    public void unusedConnectionOverTlsIsClosedAfterTheIdleTimeout() throws Exception {
        assertUnusedConnectionIsClosedAfterTheIdleTimeout(createKeyStore("idle"));
    }

    private void assertUnusedConnectionIsClosedAfterTheIdleTimeout(KeyStore keyStore) throws Exception {
        HttpServer server = track(new HttpServer(keyStore == null ? null : createServerContext(keyStore), request -> ok("{}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));
        SSLContext clientContext = keyStore == null ? null : createClientContext((X509Certificate)keyStore.getCertificate(keyStore.aliases().nextElement()));
        HttpEndpointClient client = track(new HttpEndpointClient("test", new HttpProxy(HttpProxyProtocol.SOCKS5, "127.0.0.1", proxy.getPort()), clientContext,
                Duration.ofSeconds(5), Duration.ofMillis(500)));
        String uri = (keyStore == null ? "http" : "https") + "://node.onion:8332/";
        assertEquals(200, send(client, uri).statusCode());
        sleep(1500);
        assertEquals(200, send(client, uri).statusCode());
        assertEquals(2, proxy.connectTargets.size());
    }

    @Test
    public void reusedConnectionClosedUnansweredIsSentAgain() throws Exception {
        //The first connection answers one request and closes on the next, as a server closing an idle connection as the request arrives
        HttpServer server = track(new HttpServer(null, perConnection((connection, request) -> connection == 1 && request == 2 ? null : ok("{}"))));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        String uri = "http://127.0.0.1:" + server.getPort() + "/";
        assertEquals(200, post(client, uri).statusCode());
        assertEquals(200, post(client, uri).statusCode());
        assertEquals(2, server.connections.get());
        assertEquals(3, server.requests.get());
    }

    @Test
    public void reusedConnectionResetUnansweredIsSentAgain() throws Exception {
        HttpServer server = track(new HttpServer(null, perConnection((connection, request) -> connection == 1 && request == 2 ? RESET : ok("{}"))));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        String uri = "http://127.0.0.1:" + server.getPort() + "/";
        assertEquals(200, post(client, uri).statusCode());
        assertEquals(200, post(client, uri).statusCode());
        assertEquals(2, server.connections.get());
        assertEquals(3, server.requests.get());
    }

    @Test
    public void resentRequestClosedUnansweredIsNotSentAgain() throws Exception {
        HttpServer server = track(new HttpServer(null, perConnection((connection, request) -> connection == 1 && request == 1 ? ok("{}") : null)));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        String uri = "http://127.0.0.1:" + server.getPort() + "/";
        assertEquals(200, post(client, uri).statusCode());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(EOFException.class,
                () -> client.send("POST", new URI(uri), null, "application/json", "{}".getBytes(StandardCharsets.UTF_8), READ_TIMEOUT)));
        assertEquals(2, server.connections.get());
        assertEquals(3, server.requests.get());
    }

    @Test
    public void refusedConnectionThrowsConnectException() throws Exception {
        int port;
        try(ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = serverSocket.getLocalPort();
        }
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(ConnectException.class,
                () -> client.send("GET", new URI("http://127.0.0.1:" + port + "/"), null, null, null, READ_TIMEOUT)));
    }

    @Test
    public void givenSslContextAuthenticatesWithoutHostnameAndCertificatesAreReturned() throws Exception {
        KeyStore keyStore = createKeyStore("pinned");
        HttpServer server = track(new HttpServer(createServerContext(keyStore), request -> ok("{}")));
        SocksProxy proxy = track(new SocksProxy(server.getPort()));

        X509Certificate pinned = (X509Certificate)keyStore.getCertificate("pinned");
        HttpEndpointClient client = track(new HttpEndpointClient("test", new HttpProxy(HttpProxyProtocol.SOCKS5, "127.0.0.1", proxy.getPort()), createClientContext(pinned),
                CONNECT_TIMEOUT, IDLE_TIMEOUT));
        for(int i = 0; i < 2; i++) {
            HttpEndpointClient.Response response = send(client, "https://somenode.onion:8332/");
            assertEquals(200, response.statusCode());
            assertEquals(pinned, response.serverCertificates()[0]);
        }
        assertEquals(1, server.connections.get());
    }

    @Test
    public void givenSslContextRefusesAnotherCertificate() throws Exception {
        HttpServer server = track(new HttpServer(createServerContext(createKeyStore("other")), request -> ok("{}")));
        X509Certificate pinned = (X509Certificate)createKeyStore("pinned").getCertificate("pinned");
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, createClientContext(pinned), CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class,
                () -> client.send("GET", new URI("https://127.0.0.1:" + server.getPort() + "/"), null, null, null, READ_TIMEOUT)));
        assertEquals(0, server.requests.get());
    }

    @Test
    public void defaultSslContextRefusesSelfSignedCertificate() throws Exception {
        HttpServer server = track(new HttpServer(createServerContext(createKeyStore("self")), request -> ok("{}")));
        HttpEndpointClient client = track(new HttpEndpointClient("test", null, null, CONNECT_TIMEOUT, IDLE_TIMEOUT));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class,
                () -> client.send("GET", new URI("https://127.0.0.1:" + server.getPort() + "/"), null, null, null, READ_TIMEOUT)));
        assertEquals(0, server.requests.get());
    }

    private <T extends Closeable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    private static HttpEndpointClient.Response post(HttpEndpointClient client, String uri) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> client.send("POST", new URI(uri), null, "application/json", "{}".getBytes(StandardCharsets.UTF_8), READ_TIMEOUT));
    }

    //Numbers each connection from 1 in the order of its first request, and each request on it from 1, as each connection is served on its own thread
    private static Function<String, String> perConnection(BiFunction<Integer, Integer, String> handler) {
        AtomicInteger connections = new AtomicInteger();
        ThreadLocal<int[]> connectionRequests = ThreadLocal.withInitial(() -> new int[] {connections.incrementAndGet(), 0});
        return request -> {
            int[] counts = connectionRequests.get();
            return handler.apply(counts[0], ++counts[1]);
        };
    }

    private static HttpEndpointClient.Response send(HttpEndpointClient client, String uri) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> client.send("GET", new URI(uri), null, null, null, READ_TIMEOUT));
    }

    private static String ok(String body) {
        return response(200, body, "Content-Type: application/json\r\n");
    }

    private static String response(int status, String body, String headers) {
        return "HTTP/1.1 " + status + " Status\r\n" + headers + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private KeyStore createKeyStore(String alias) throws Exception {
        File keyStoreFile = tempDir.resolve(alias + ".p12").toFile();
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + alias, "-validity", "2",
                "-keystore", keyStoreFile.getAbsolutePath(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try(FileInputStream fis = new FileInputStream(keyStoreFile)) {
            keyStore.load(fis, STORE_PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static SSLContext createServerContext(KeyStore keyStore) throws Exception {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    private static SSLContext createClientContext(X509Certificate pinned) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("pinned", pinned);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while((b = in.read()) != -1 && b != '\n') {
            if(b != '\r') {
                line.append((char)b);
            }
        }
        return b == -1 && line.isEmpty() ? null : line.toString();
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    //An HTTP/1.1 server that keeps connections open, answering each request with the handler's response, or closing the connection where the handler returns null
    private static class HttpServer implements Closeable {
        private final ServerSocket serverSocket;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();

        HttpServer(SSLContext sslContext, Function<String, String> handler) throws IOException {
            serverSocket = sslContext == null ? new ServerSocket(0, 50, InetAddress.getLoopbackAddress()) :
                    sslContext.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
            daemon(() -> {
                try {
                    while(true) {
                        Socket socket = serverSocket.accept();
                        connections.incrementAndGet();
                        daemon(() -> serve(socket, handler));
                    }
                } catch(IOException e) {
                    //Closed at the end of the test
                }
            });
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void serve(Socket socket, Function<String, String> handler) {
            try(socket) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                while(true) {
                    StringBuilder request = new StringBuilder();
                    int contentLength = 0;
                    for(String line = readLine(in); ; line = readLine(in)) {
                        if(line == null) {
                            return;
                        }
                        request.append(line).append("\r\n");
                        if(line.isEmpty()) {
                            break;
                        }
                        if(line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                        }
                    }
                    request.append(new String(in.readNBytes(contentLength), StandardCharsets.UTF_8));
                    requests.incrementAndGet();

                    String response = handler.apply(request.toString());
                    if(response == RESET) {
                        socket.setSoLinger(true, 0);
                        return;
                    } else if(response == null) {
                        return;
                    }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    if(response.contains("Connection: close")) {
                        return;
                    }
                }
            } catch(IOException e) {
                //A failed handshake or a closed connection
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    //A SOCKS4a and SOCKS5 proxy that records the target of each CONNECT and relays the connection to a local port
    private static class SocksProxy implements Closeable {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<String> connectTargets = new CopyOnWriteArrayList<>();
        //Delays the reply to each CONNECT, as Tor does while building a circuit to an onion service
        private volatile long connectDelayMillis;

        SocksProxy(int relayPort) throws IOException {
            daemon(() -> {
                try {
                    while(true) {
                        Socket socket = serverSocket.accept();
                        daemon(() -> serve(socket, relayPort));
                    }
                } catch(IOException e) {
                    //Closed at the end of the test
                }
            });
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void serve(Socket socket, int relayPort) {
            try(socket; Socket relay = new Socket(InetAddress.getLoopbackAddress(), relayPort)) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                int version = in.readUnsignedByte();
                if(version == 5) {
                    in.readNBytes(in.readUnsignedByte());
                    out.write(new byte[] {5, 0});
                    in.readNBytes(3);
                    String address = switch(in.readUnsignedByte()) {
                        case 1 -> "IPV4:" + InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                        case 3 -> "DOMAIN:" + new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                        case 4 -> "IPV6:" + InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
                        default -> throw new IOException("Unknown SOCKS5 address type");
                    };
                    connectTargets.add("SOCKS5 " + address + ":" + in.readUnsignedShort());
                    Thread.sleep(connectDelayMillis);
                    out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                } else if(version == 4) {
                    in.readUnsignedByte();
                    int port = in.readUnsignedShort();
                    byte[] ip = in.readNBytes(4);
                    readNullTerminated(in);
                    boolean socks4a = ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0;
                    String address = socks4a ? "SOCKS4A DOMAIN:" + readNullTerminated(in) : "SOCKS4 IPV4:" + InetAddress.getByAddress(ip).getHostAddress();
                    connectTargets.add(address + ":" + port);
                    out.write(new byte[] {0, 0x5A, 0, 0, 0, 0, 0, 0});
                } else {
                    return;
                }
                out.flush();

                Thread upstream = daemon(() -> relay(socket, relay));
                relay(relay, socket);
                upstream.join(1000);
            } catch(IOException | InterruptedException e) {
                //Closed by either end
            }
        }

        private static String readNullTerminated(InputStream in) throws IOException {
            StringBuilder value = new StringBuilder();
            for(int b = in.read(); b > 0; b = in.read()) {
                value.append((char)b);
            }
            return value.toString();
        }

        private static void relay(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
                to.shutdownOutput();
            } catch(IOException e) {
                //Closed by either end
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
