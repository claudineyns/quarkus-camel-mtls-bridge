package io.github.claudineyns.bridge.filter;

import io.github.claudineyns.bridge.config.BridgeConfig;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ClientCertFilter {

    @Inject
    Router router;

    @Inject
    BridgeConfig config;

    void onStart(@Observes final StartupEvent event) {
        final String dnHeader                    = config.dnHeaderName();
        final Optional<String> issuerDnHeader    = config.cert().issuerDnHeaderName().filter(h -> !h.isBlank());
        final Optional<String> serialHeader      = config.cert().serialHeaderName().filter(h -> !h.isBlank());
        final Optional<String> notBeforeHeader   = config.cert().notBeforeHeaderName().filter(h -> !h.isBlank());
        final Optional<String> notAfterHeader    = config.cert().notAfterHeaderName().filter(h -> !h.isBlank());
        final Optional<String> fingerprintHeader = config.cert().fingerprintHeaderName().filter(h -> !h.isBlank());
        final Optional<String> sanHeader         = config.cert().sanHeaderName().filter(h -> !h.isBlank());
        final Optional<String> pemHeader         = config.cert().pemHeaderName().filter(h -> !h.isBlank());

        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            final SSLSession sslSession = ctx.request().sslSession();
            if (sslSession != null) {
                try {
                    final Certificate[] certs = sslSession.getPeerCertificates();
                    if (certs != null && certs.length > 0) {
                        final X509Certificate cert = (X509Certificate) certs[0];

                        ctx.request().headers().add(dnHeader,
                                cert.getSubjectX500Principal().getName());

                        issuerDnHeader.ifPresent(h -> ctx.request().headers().add(h,
                                cert.getIssuerX500Principal().getName()));

                        serialHeader.ifPresent(h -> ctx.request().headers().add(h,
                                cert.getSerialNumber().toString(16)));

                        notBeforeHeader.ifPresent(h -> ctx.request().headers().add(h,
                                cert.getNotBefore().toInstant().toString()));

                        notAfterHeader.ifPresent(h -> ctx.request().headers().add(h,
                                cert.getNotAfter().toInstant().toString()));

                        fingerprintHeader.ifPresent(h -> {
                            try {
                                final byte[] sha256 = MessageDigest.getInstance("SHA-256")
                                        .digest(cert.getEncoded());
                                ctx.request().headers().add(h, HexFormat.of().formatHex(sha256));
                            } catch (CertificateEncodingException | NoSuchAlgorithmException ignored) {}
                        });

                        sanHeader.ifPresent(h -> {
                            try {
                                final Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                                if (sans != null && !sans.isEmpty()) {
                                    final StringBuilder sb = new StringBuilder();
                                    for (final List<?> san : sans) {
                                        if (!sb.isEmpty()) sb.append(',');
                                        sb.append(sanTypeName((Integer) san.get(0))).append(':');
                                        final Object val = san.get(1);
                                        sb.append(val instanceof byte[] b
                                                ? Base64.getEncoder().encodeToString(b)
                                                : val.toString());
                                    }
                                    if (!sb.isEmpty()) ctx.request().headers().add(h, sb.toString());
                                }
                            } catch (Exception ignored) {}
                        });

                        pemHeader.ifPresent(h -> {
                            try {
                                ctx.request().headers().add(h,
                                        Base64.getEncoder().encodeToString(cert.getEncoded()));
                            } catch (CertificateEncodingException ignored) {}
                        });
                    }
                } catch (final SSLPeerUnverifiedException ignored) {}
            }
            ctx.next();
        });
    }

    private static String sanTypeName(final int type) {
        return switch (type) {
            case 1 -> "email";
            case 2 -> "DNS";
            case 6 -> "URI";
            case 7 -> "IP";
            default -> "type" + type;
        };
    }
}
