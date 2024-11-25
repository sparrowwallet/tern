package com.sparrowwallet.tern.http.client;

import com.google.common.net.HostAndPort;
import io.reactivex.Observable;

import java.util.Map;
import java.util.Optional;

public class HttpClientService extends JettyHttpClientService {
    private static final int DEFAULT_REQUEST_TIMEOUT = 120000;

    public HttpClientService() {
        this(DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpClientService(int requestTimeout) {
        this(requestTimeout, null);
    }

    public HttpClientService(HostAndPort torProxy) {
        this(DEFAULT_REQUEST_TIMEOUT, new HttpProxySupplier(torProxy));
    }

    public HttpClientService(HttpProxySupplier httpProxySupplier) {
        this(DEFAULT_REQUEST_TIMEOUT, httpProxySupplier);
    }

    public HttpClientService(int requestTimeout, HttpProxySupplier httpProxySupplier) {
        super(requestTimeout, httpProxySupplier);
    }

    public <T> T requestJson(String url, Class<T> responseType, Map<String, String> headers) throws Exception {
        return getHttpClient(HttpUsage.DEFAULT).getJson(url, responseType, headers);
    }

    public <T> Observable<Optional<T>> postJson(String url, Class<T> responseType, Map<String, String> headers, Object body) {
        return getHttpClient(HttpUsage.DEFAULT).postJson(url, responseType, headers, body).toObservable();
    }

    public String postString(String url, Map<String, String> headers, String contentType, String content) throws Exception {
        IHttpClient httpClient = getHttpClient(HttpUsage.DEFAULT);
        return AsyncUtil.getInstance().blockingGet(httpClient.postString(url, headers, contentType, content)).get();
    }

    public HostAndPort getTorProxy() {
        return getHttpProxySupplier().getTorProxy();
    }

    public void setTorProxy(HostAndPort torProxy) {
        //Ensure all http clients are shutdown first
        stop();
        getHttpProxySupplier()._setTorProxy(torProxy);
    }

    @Override
    public HttpProxySupplier getHttpProxySupplier() {
        return (HttpProxySupplier)super.getHttpProxySupplier();
    }
}
