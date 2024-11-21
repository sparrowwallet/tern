package com.sparrowwallet.tern.http.client;

public class HttpNetworkException extends HttpException {
    public HttpNetworkException(String message) {
        super(message);
    }

    public HttpNetworkException(Exception cause) {
        super(cause);
    }
}
