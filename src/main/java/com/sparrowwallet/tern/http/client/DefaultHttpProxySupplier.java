package com.sparrowwallet.tern.http.client;

import java.util.Optional;

public class DefaultHttpProxySupplier implements IHttpProxySupplier {
    private final String host;
    private final int port;
    private final HttpProxy httpProxy;

    public DefaultHttpProxySupplier(String host, int port) {
        this.host = host;
        this.port = port;
        this.httpProxy = computeHttpProxy(host, port);
    }

    private HttpProxy computeHttpProxy(String host, int port) {
        if(host == null || host.isEmpty() || port < 0) {
            return null;
        }

        return new HttpProxy(HttpProxyProtocol.SOCKS, host, port);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public Optional<HttpProxy> getHttpProxy(HttpUsage httpUsage) {
        return Optional.ofNullable(httpProxy);
    }

    @Override
    public void changeIdentity() {
        //not implemented
    }
}
