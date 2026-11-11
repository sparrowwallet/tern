package com.sparrowwallet.tern.http.client;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Socks4Proxy;
import org.eclipse.jetty.client.Socks5Proxy;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class JettyHttpClientService implements IHttpClientService {
    private static final Logger log = LoggerFactory.getLogger(JettyHttpClientService.class);
    private static final String NAME = "HttpClient";
    public static final long DEFAULT_TIMEOUT = 30000;

    // limit changing Tor identity on network error every 4 minutes
    private static final int RATE_CHANGE_IDENTITY_ON_NETWORK_ERROR = 4 * 60 * 1000;

    protected Map<HttpUsage, JettyHttpClient> httpClients; // used by Sparrow
    private final IHttpProxySupplier httpProxySupplier;
    private final long requestTimeout;

    public JettyHttpClientService(long requestTimeout, IHttpProxySupplier httpProxySupplier) {
        this.httpProxySupplier = httpProxySupplier != null ? httpProxySupplier : computeHttpProxySupplierDefault();
        this.requestTimeout = requestTimeout;
        this.httpClients = new ConcurrentHashMap<>();
    }

    public JettyHttpClientService(long requestTimeout) {
        this(requestTimeout, null);
    }

    public JettyHttpClientService() {
        this(DEFAULT_TIMEOUT);
    }

    protected static IHttpProxySupplier computeHttpProxySupplierDefault() {
        return new IHttpProxySupplier() {
            @Override
            public Optional<HttpProxy> getHttpProxy(HttpUsage httpUsage) {
                return Optional.empty();
            }

            @Override
            public void changeIdentity() {
            }
        };
    }

    @Override
    public synchronized JettyHttpClient getHttpClient(HttpUsage httpUsage) {
        //Synchronized with stop() and changeIdentity(), and with a subclass changing the proxy under this lock, so that no client is created between stopping the clients
        //and changing the settings they were created from
        return httpClients.computeIfAbsent(httpUsage, usage -> {
            if(log.isDebugEnabled()) {
                log.debug("+httpClient[" + usage + "]");
            }
            return computeHttpClient(usage);
        });
    }

    protected JettyHttpClient computeHttpClient(HttpUsage httpUsage) {
        Consumer<Exception> onNetworkError = computeOnNetworkError();
        HttpClient httpClient = computeJettyClient(httpUsage);
        return new JettyHttpClient(onNetworkError, httpClient, requestTimeout, httpUsage);
    }

    protected HttpClient computeJettyClient(HttpUsage httpUsage) {
        // we use jetty for proxy SOCKS support
        HttpClient jettyHttpClient = new HttpClient();

        // prevent user-agent tracking
        jettyHttpClient.setUserAgentField(null);
        jettyHttpClient.setFollowRedirects(httpUsage.isFollowRedirects());

        // configure
        configureProxy(jettyHttpClient, httpUsage);
        configureThread(jettyHttpClient, httpUsage);

        return jettyHttpClient;
    }

    protected Consumer<Exception> computeOnNetworkError() {
        RateLimiter rateLimiter = new RateLimiter(1, RATE_CHANGE_IDENTITY_ON_NETWORK_ERROR);
        return e -> {
            if(!rateLimiter.tryAcquire()) {
                if(log.isDebugEnabled()) {
                    log.debug("onNetworkError: not changing Tor identity (too many recent attempts)");
                }
                return;
            }
            // change Tor identity on network error
            httpProxySupplier.changeIdentity();
        };
    }

    protected void configureProxy(HttpClient jettyHttpClient, HttpUsage httpUsage) {
        Optional<HttpProxy> httpProxyOptional = httpProxySupplier.getHttpProxy(httpUsage);
        if(httpProxyOptional != null && httpProxyOptional.isPresent()) {
            HttpProxy httpProxy = httpProxyOptional.get();
            if(log.isDebugEnabled()) {
                log.debug("+httpClient: proxy=" + httpProxy);
            }
            jettyHttpClient.getProxyConfiguration().addProxy(computeJettyProxy(httpProxy));
        } else {
            if(log.isDebugEnabled()) {
                log.debug("+httpClient: no proxy");
            }
        }
    }

    protected void configureThread(HttpClient jettyHttpClient, HttpUsage httpUsage) {
        String name = NAME + "-" + httpUsage.toString();

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(name);
        threadPool.setDaemon(true);
        jettyHttpClient.setExecutor(threadPool);
        jettyHttpClient.setScheduler(new ScheduledExecutorScheduler(name + "-scheduler", true));
    }

    protected static ProxyConfiguration.Proxy computeJettyProxy(HttpProxy httpProxy) {
        return switch(httpProxy.getProtocol()) {
            case SOCKS -> new Socks4Proxy(httpProxy.getHost(), httpProxy.getPort());
            case SOCKS5 -> new Socks5Proxy(httpProxy.getHost(), httpProxy.getPort());
            case HTTP -> new org.eclipse.jetty.client.HttpProxy(httpProxy.getHost(), httpProxy.getPort());
        };
    }

    @Override
    public synchronized void stop() {
        for(JettyHttpClient httpClient : httpClients.values()) {
            httpClient.stop();
        }
        httpClients.clear();
    }

    @Override
    public synchronized void changeIdentity() {
        //Holds the lock getHttpClient() takes, so that no client opens a connection on the previous identity between the two
        stop();
        httpProxySupplier.changeIdentity();
    }

    public IHttpProxySupplier getHttpProxySupplier() {
        return httpProxySupplier;
    }
}
