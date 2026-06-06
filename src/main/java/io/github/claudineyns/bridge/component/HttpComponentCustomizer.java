package io.github.claudineyns.bridge.component;

import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.component.vertx.http.VertxHttpComponent;
import org.apache.camel.quarkus.main.events.BeforeConfigure;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpComponentCustomizer {

    @Inject
    @ConfigProperty(name = "bridge.http-client.max-pool-size")
    int maxPoolSize;

    @Inject
    @ConfigProperty(name = "bridge.http-client.verify-host")
    boolean verifyHost;

    void configure(@Observes final BeforeConfigure event) {
        final VertxHttpComponent component = event.getCamelContext()
                .getComponent("vertx-http", VertxHttpComponent.class);
        component.setWebClientOptions(
                new WebClientOptions()
                        .setTrustAll(true)
                        .setVerifyHost(verifyHost)
                        .setMaxPoolSize(maxPoolSize)
                        .setProtocolVersion(HttpVersion.HTTP_2)
                        .setUseAlpn(true)
        );
        component.setVertxHttpBinding(new ProxyVertxHttpBinding());
    }
}
