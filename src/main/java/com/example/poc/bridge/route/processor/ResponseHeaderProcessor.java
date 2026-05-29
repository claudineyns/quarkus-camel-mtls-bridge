package com.example.poc.bridge.route.processor;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

@ApplicationScoped
public class ResponseHeaderProcessor implements Processor {

    @Override
    public void process(final Exchange exchange) {
        exchange.getMessage().removeHeaders("Camel*", Exchange.HTTP_RESPONSE_CODE);
    }
}
