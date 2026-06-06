package com.example.poc.bridge.component;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import org.apache.camel.Exchange;
import org.apache.camel.component.vertx.http.DefaultVertxHttpBinding;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clears inbound request headers from the exchange before populating it with
 * the backend response headers, preventing request headers from leaking into
 * the response sent to the client.
 */
public class ProxyVertxHttpBinding extends DefaultVertxHttpBinding {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyVertxHttpBinding.class);

    @Override
    public void populateResponseHeaders(Exchange exchange,
                                        HttpResponse<Buffer> response,
                                        HeaderFilterStrategy headerFilterStrategy) {
        LOG.trace("outbound protocol: {} → backend HTTP {}", response.version(), response.statusCode());
        exchange.getMessage().removeHeaders("*");
        super.populateResponseHeaders(exchange, response, headerFilterStrategy);
    }
}
