package com.example.poc.bridge.component;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.quarkus.main.events.BeforeConfigure;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

@ApplicationScoped
public class HttpComponentCustomizer {

    void configure(@Observes final BeforeConfigure event) {
        final HttpComponent http = event.getCamelContext().getComponent("http", HttpComponent.class);
        http.setCopyHeaders(false);
        http.setClientConnectionManager(buildConnectionManager());
    }

    private HttpClientConnectionManager buildConnectionManager() {
        final TlsSocketStrategy tlsStrategy = new DefaultClientTlsStrategy(
                buildTrustAllSslContext(),
                NoopHostnameVerifier.INSTANCE
        );
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsStrategy)
                .setMaxConnPerRoute(25)
                .setMaxConnTotal(25)
                .build();
    }

    private SSLContext buildTrustAllSslContext() {
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}
                @Override
                public void checkServerTrusted(final X509Certificate[] chain, final String authType) {}
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return sslContext;
        } catch (final NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("Failed to build trust-all SSLContext for outbound connections", e);
        }
    }
}
