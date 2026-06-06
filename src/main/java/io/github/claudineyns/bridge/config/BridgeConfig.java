package io.github.claudineyns.bridge.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "bridge")
public interface BridgeConfig {

    TargetConfig target();

    @WithName("dn.header-name")
    @WithDefault("x-cert-client-subject-dn")
    String dnHeaderName();

    CertConfig cert();

    interface TargetConfig {
        String url();
    }

    interface CertConfig {
        @WithName("issuer-dn.header-name")
        Optional<String> issuerDnHeaderName();

        @WithName("serial.header-name")
        Optional<String> serialHeaderName();

        @WithName("not-before.header-name")
        Optional<String> notBeforeHeaderName();

        @WithName("not-after.header-name")
        Optional<String> notAfterHeaderName();

        @WithName("fingerprint.header-name")
        Optional<String> fingerprintHeaderName();

        @WithName("san.header-name")
        Optional<String> sanHeaderName();

        @WithName("pem.header-name")
        Optional<String> pemHeaderName();
    }
}
