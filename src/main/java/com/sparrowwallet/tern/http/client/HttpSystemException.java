package com.sparrowwallet.tern.http.client;

public class HttpSystemException extends HttpException {
    public HttpSystemException(String message) {
        super(message);
    }

    public HttpSystemException(Exception cause) {
        super(cause);
    }
}
