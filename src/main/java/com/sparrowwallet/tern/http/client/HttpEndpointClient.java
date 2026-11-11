package com.sparrowwallet.tern.http.client;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A client for requests to a single HTTP server, with its own connection pool, proxy, TLS context and timeouts.
 * Unlike HttpClientService, a response is returned whatever its status, and redirects are never followed.
 * Connections are kept open between requests for the idle timeout given, and must be released with close().
 */
public class HttpEndpointClient implements Closeable {
    private static final int MAX_RESPONSE_LENGTH = Integer.MAX_VALUE;

    private final HttpClient httpClient;
    //Connections that have carried a response and are kept open for the next request
    private final Set<Connection> reusedConnections = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * @param name the name given to the client's threads
     * @param proxy the proxy to connect through, or null to connect directly. A SOCKS proxy is given the server's hostname to resolve.
     * @param sslContext the context that authenticates an https server, or null for the default trust store and hostname verification.
     *                   The hostname is not verified against a context given here, which must authenticate the server itself, for example with a pinned certificate.
     * @param connectTimeout the time allowed to open a connection, including through the proxy
     * @param idleTimeout the time an unused connection is kept open for the next request
     */
    public HttpEndpointClient(String name, HttpProxy proxy, SSLContext sslContext, Duration connectTimeout, Duration idleTimeout) throws IOException {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        if(sslContext != null) {
            sslContextFactory.setSslContext(sslContext);
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
        }

        httpClient = new HttpClient();
        httpClient.setSslContextFactory(sslContextFactory);
        httpClient.setUserAgentField(null);
        httpClient.setFollowRedirects(false);
        httpClient.setConnectTimeout(connectTimeout.toMillis());
        httpClient.setIdleTimeout(idleTimeout.toMillis());
        if(proxy != null) {
            httpClient.getProxyConfiguration().addProxy(JettyHttpClientService.computeJettyProxy(proxy));
        }

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(name);
        threadPool.setDaemon(true);
        httpClient.setExecutor(threadPool);
        httpClient.setScheduler(new ScheduledExecutorScheduler(name + "-scheduler", true));

        try {
            httpClient.start();
        } catch(Exception e) {
            throw new IOException("Could not start HTTP client", e);
        }

        //Returns authentication challenges and other responses as received, rather than acting on them. Cleared once started, as starting adds the default handlers.
        httpClient.getProtocolHandlers().clear();
    }

    /**
     * Sends a request and waits for the complete response. A request sent on a connection kept open from an earlier response, which the server closes without answering,
     * is sent once more on a new connection.
     *
     * @param contentType the content type of the body, or null for none
     * @param body the request body, or null for none
     * @param readTimeout the time allowed without receiving data while waiting for the response, or zero to wait indefinitely
     * @throws SocketTimeoutException if the read timeout expired
     * @throws EOFException if the connection was closed before any response was received
     * @throws IOException if the request could not be sent or the response could not be read, with the cause as thrown by the connection where it was an IOException
     */
    public Response send(String method, URI uri, Map<String, String> headers, String contentType, byte[] body, Duration readTimeout) throws IOException {
        return send(method, uri, headers, contentType, body, readTimeout, true);
    }

    private Response send(String method, URI uri, Map<String, String> headers, String contentType, byte[] body, Duration readTimeout, boolean resendIfReused) throws IOException {
        Request request = httpClient.newRequest(uri).method(method).idleTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if(headers != null) {
            request.headers(fields -> headers.forEach(fields::put));
        }
        if(body != null) {
            request.body(new BytesRequestContent(contentType, body));
        }

        AtomicReference<X509Certificate[]> serverCertificates = new AtomicReference<>(new X509Certificate[0]);
        AtomicBoolean responseBegun = new AtomicBoolean();
        request.onResponseBegin(response -> {
            responseBegun.set(true);
            Connection connection = response.getRequest().getConnection();
            if(connection != null) {
                reusedConnections.add(connection);
            }
            EndPoint.SslSessionData sslSessionData = connection == null ? null : connection.getSslSessionData();
            if(sslSessionData != null && sslSessionData.peerCertificates() != null) {
                serverCertificates.set(sslSessionData.peerCertificates());
            }
        });

        CompletableFuture<ContentResponse> future = new CompletableResponseListener(request, MAX_RESPONSE_LENGTH).send();
        try {
            ContentResponse response = future.get();
            return new Response(response.getStatus(), response.getContent(), serverCertificates.get());
        } catch(InterruptedException e) {
            request.abort(e);
            Thread.currentThread().interrupt();
            InterruptedIOException interruptedIOException = new InterruptedIOException("Interrupted waiting for " + uri);
            interruptedIOException.initCause(e);
            throw interruptedIOException;
        } catch(ExecutionException e) {
            Throwable cause = e.getCause();
            if(cause instanceof EOFException && responseBegun.get()) {
                throw new IOException("Connection closed before the response from " + uri.getAuthority() + " was complete", cause);
            } else if(cause instanceof EOFException && resendIfReused && reusedConnections.contains(request.getConnection())) {
                //A server closes a connection it has kept open once idle, and may do so as this request is sent on it, before reading the request. Sent once more
                //on a new connection, as HttpURLConnection does. A request on a new connection that is closed unanswered was refused, and is not sent again.
                return send(method, uri, headers, contentType, body, readTimeout, false);
            } else if(cause instanceof IOException ioException) {
                throw ioException;
            } else if(cause instanceof TimeoutException) {
                SocketTimeoutException socketTimeoutException = new SocketTimeoutException(cause.getMessage());
                socketTimeoutException.initCause(cause);
                throw socketTimeoutException;
            }
            throw new IOException(cause);
        }
    }

    @Override
    public void close() {
        try {
            httpClient.stop();
        } catch(Exception e) {
            //Ignore, the client is no longer used
        }
    }

    /**
     * @param serverCertificates the certificates the server presented on the connection that carried the response, or empty for http
     */
    public record Response(int statusCode, byte[] body, X509Certificate[] serverCertificates) {}
}
