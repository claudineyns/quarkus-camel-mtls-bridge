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
                .removeHeader("*")
                .log(LoggingLevel.DEBUG, LOGGER,
                        "→ REQ  ${header.CamelHttpMethod} ${header.CamelHttpPath} | headers: ${headers}")
                .to("{{bridge.target.url}}?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .process(responseHeaderProcessor)
                .log(LoggingLevel.DEBUG, LOGGER,
                        "← RESP ${header.CamelHttpResponseCode} | headers: ${headers}");
    }
}
