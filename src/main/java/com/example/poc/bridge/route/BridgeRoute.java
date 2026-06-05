package com.example.poc.bridge.route;

import com.example.poc.bridge.route.processor.ConnectivityErrorProcessor;
import com.example.poc.bridge.route.processor.ResponseHeaderProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

import java.io.IOException;


@ApplicationScoped
public class BridgeRoute extends RouteBuilder {

    private static final String LOGGER = "com.example.poc.bridge";

    @Inject
    ConnectivityErrorProcessor connectivityErrorProcessor;

    @Inject
    ResponseHeaderProcessor responseHeaderProcessor;

    @Override
    public void configure() {

        onException(IOException.class)
                .handled(true)
                .process(connectivityErrorProcessor);

        from("platform-http:/?matchOnUriPrefix=true")
                .setProperty("bridge.requestPath", header(Exchange.HTTP_PATH))
                .removeHeader(Exchange.HTTP_URI)
                .removeHeader("Host")
                .removeHeader("*")
                .removeHeader("Content-Length")
                .removeHeader("Transfer-Encoding")
                .log(LoggingLevel.INFO, LOGGER,
                        "→ INBOUND  [${header.CamelHttpMethod} ${header.CamelHttpPath}] headers=${headers}")
                .to("vertx-http:{{bridge.target.url}}?throwExceptionOnFailure=false")
                .log(LoggingLevel.INFO, LOGGER,
                        "← BACKEND  [${header.CamelHttpResponseCode}] headers=${headers}")
                .process(responseHeaderProcessor)
                .log(LoggingLevel.INFO, LOGGER,
                        "← OUTBOUND [${header.CamelHttpResponseCode}] headers=${headers}");
    }
}
