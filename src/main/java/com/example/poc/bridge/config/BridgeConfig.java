package com.example.poc.bridge.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "bridge")
public interface BridgeConfig {

    TargetConfig target();

    @WithName("dn.header-name")
    @WithDefault("x-cert-client-subject-dn")
    String dnHeaderName();

    interface TargetConfig {
        String url();
    }
}
