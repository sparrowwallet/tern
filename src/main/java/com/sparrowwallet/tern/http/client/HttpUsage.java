package com.sparrowwallet.tern.http.client;

import java.util.Objects;

public class HttpUsage {
    public static final HttpUsage DEFAULT = new HttpUsage("Default");

    private final String name;
    private final boolean followRedirects;

    public HttpUsage(String name) {
        this(name, true);
    }

    /**
     * @param followRedirects false to return a redirect response as an HttpResponseException rather than following it, for requests whose body must reach only the url given
     */
    public HttpUsage(String name, boolean followRedirects) {
        this.name = name;
        this.followRedirects = followRedirects;
    }

    public String getName() {
        return name;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) {
            return true;
        }
        if(o == null || getClass() != o.getClass()) {
            return false;
        }
        HttpUsage httpUsage = (HttpUsage) o;
        return followRedirects == httpUsage.followRedirects && Objects.equals(name, httpUsage.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, followRedirects);
    }
}
