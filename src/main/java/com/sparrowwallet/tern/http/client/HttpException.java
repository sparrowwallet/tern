package com.sparrowwallet.tern.http.client;

public abstract class HttpException extends Exception {

    public HttpException(Exception cause) {
        super(cause);
    }

    public HttpException(String message) {
        super(message);
    }
}
