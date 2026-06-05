package com.example.poc.bridge.component;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import org.apache.camel.Exchange;
import org.apache.camel.component.vertx.http.DefaultVertxHttpBinding;
import org.apache.camel.spi.HeaderFilterStrategy;

/**
 * Clears inbound request headers from the exchange before populating it with
 * the backend response headers, preventing request headers from leaking into
 * the response sent to the client.
 */
public class ProxyVertxHttpBinding extends DefaultVertxHttpBinding {

    @Override
    public void populateResponseHeaders(Exchange exchange,
                                        HttpResponse<Buffer> response,
                                        HeaderFilterStrategy headerFilterStrategy) {
        exchange.getMessage().removeHeaders("*");
        super.populateResponseHeaders(exchange, response, headerFilterStrategy);
    }
}
