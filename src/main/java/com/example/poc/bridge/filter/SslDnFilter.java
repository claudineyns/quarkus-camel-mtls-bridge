package com.example.poc.bridge.filter;

import com.example.poc.bridge.config.BridgeConfig;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

@ApplicationScoped
public class SslDnFilter {

    @Inject
    Router router;

    @Inject
    BridgeConfig config;

    void onStart(@Observes final StartupEvent event) {
        final String headerName = config.dnHeaderName();
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
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
        });
    }
}
