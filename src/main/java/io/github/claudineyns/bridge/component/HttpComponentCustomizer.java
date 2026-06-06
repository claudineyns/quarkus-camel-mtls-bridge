package io.github.claudineyns.bridge.component;

import io.github.claudineyns.bridge.config.BridgeConfig;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.component.vertx.http.VertxHttpComponent;
import org.apache.camel.quarkus.main.events.BeforeConfigure;

@ApplicationScoped
public class HttpComponentCustomizer {

    @Inject
    BridgeConfig config;

    void configure(@Observes final BeforeConfigure event) {
        final VertxHttpComponent component = event.getCamelContext()
                .getComponent("vertx-http", VertxHttpComponent.class);
        component.setWebClientOptions(
                new WebClientOptions()
                        .setTrustAll(true)
                        .setVerifyHost(config.httpClient().verifyHost())
                        .setMaxPoolSize(config.httpClient().maxPoolSize())
                        .setProtocolVersion(HttpVersion.HTTP_2)
                        .setUseAlpn(true)
        );
        component.setVertxHttpBinding(new ProxyVertxHttpBinding());
    }
}
