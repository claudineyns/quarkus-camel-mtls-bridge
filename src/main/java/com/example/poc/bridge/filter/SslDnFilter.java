package com.example.poc.bridge.filter;

import com.example.poc.bridge.config.BridgeConfig;
import io.quarkus.vertx.http.runtime.filters.Filter;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

@ApplicationScoped
public class SslDnFilter implements Filter {

    @Inject
    BridgeConfig config;

    @Override
    public Handler<RoutingContext> getHandler() {
        final String headerName = config.dnHeaderName();
        return ctx -> {
            final SSLSession sslSession = ctx.request().sslSession();
            if (sslSession != null) {
                try {
                    final Certificate[] certs = sslSession.getPeerCertificates();
                    if (certs != null && certs.length > 0) {
                        final String dn = ((X509Certificate) certs[0])
                                .getSubjectX500Principal().getName();
                        ctx.request().headers().add(headerName, dn);
                    }
                } catch (final SSLPeerUnverifiedException ignored) {}
            }
            ctx.next();
        };
    }

    @Override
    public int getPriority() {
        return -1;
    }
}
