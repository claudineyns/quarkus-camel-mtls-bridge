package com.example.poc.bridge.component;

import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.camel.component.vertx.http.VertxHttpComponent;
import org.apache.camel.quarkus.main.events.BeforeConfigure;

@ApplicationScoped
public class HttpComponentCustomizer {

    void configure(@Observes final BeforeConfigure event) {
        final VertxHttpComponent component = event.getCamelContext()
                .getComponent("vertx-http", VertxHttpComponent.class);
        component.setWebClientOptions(
                new WebClientOptions()
                        .setTrustAll(true)
                        .setVerifyHost(false)
                        .setMaxPoolSize(25)
        );
        component.setVertxHttpBinding(new ProxyVertxHttpBinding());
    }
}
