module com.sparrowwallet.tern {
    requires com.google.common;
    requires io.reactivex.rxjava2;
    requires com.fasterxml.jackson.databind;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.util;
    requires org.eclipse.jetty.client;
    requires org.eclipse.jetty.http;
    requires org.slf4j;
    requires org.apache.commons.lang3;
    exports com.sparrowwallet.tern.http.client;
}