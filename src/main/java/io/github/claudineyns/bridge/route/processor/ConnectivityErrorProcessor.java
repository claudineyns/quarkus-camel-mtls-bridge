package io.github.claudineyns.bridge.route.processor;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.apache.camel.Processor;

@ApplicationScoped
public class ConnectivityErrorProcessor implements Processor {

    @Override
    public void process(final Exchange exchange) {
        final Exception cause =
                exchange.getProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, Exception.class);

        final String path = exchange.getProperty("bridge.requestPath", "/", String.class);
        final String detail = cause.getMessage() != null ? cause.getMessage() : "Upstream connection failed";

        final String body = String.format(
                "{\"type\":\"about:blank\",\"title\":\"Bad Gateway\",\"status\":502,\"detail\":\"%s\",\"instance\":\"%s\"}",
                escapeJson(detail),
                escapeJson(path)
        );

        final Message message = exchange.getMessage();
        message.removeHeaders("*");
        message.setHeader(Exchange.HTTP_RESPONSE_CODE, 502);
        message.setHeader("Content-Type", "application/problem+json");
        message.setBody(body);
    }

    private static String escapeJson(final String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
