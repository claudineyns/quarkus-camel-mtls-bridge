package com.example.poc.bridge.route;

import com.example.poc.bridge.config.BridgeConfig;
import com.example.poc.bridge.route.processor.ConnectivityErrorProcessor;
import com.example.poc.bridge.route.processor.ResponseHeaderProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

import java.io.IOException;

@ApplicationScoped
public class BridgeRoute extends RouteBuilder {

    @Inject
    BridgeConfig config;

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
                .setHeader(Exchange.HTTP_URI,
                        simple(config.target().url().replaceAll("/$", "") + "${header.CamelHttpPath}"))
                .to("http://localhost?throwExceptionOnFailure=false")
                .process(responseHeaderProcessor);
    }
}
