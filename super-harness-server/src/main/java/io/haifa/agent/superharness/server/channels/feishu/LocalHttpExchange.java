package io.haifa.agent.superharness.server.channels.feishu;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Minimal loopback {@link HttpExchange} for in-process DeerFlow thread/run calls. */
final class LocalHttpExchange extends HttpExchange {
    private final String method;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final byte[] requestBody;
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int responseCode = -1;
    private boolean closed;

    LocalHttpExchange(String method, String path, byte[] requestBody) {
        this.method = method;
        this.uri = URI.create(path);
        this.requestBody = requestBody == null ? new byte[0] : requestBody;
        this.requestHeaders.add("Content-Type", "application/json");
    }

    int responseCode() {
        return responseCode;
    }

    byte[] responseBytes() {
        return responseBody.toByteArray();
    }

    String responseText() {
        return new String(responseBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public InputStream getRequestBody() {
        return new ByteArrayInputStream(requestBody);
    }

    @Override
    public OutputStream getResponseBody() {
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
        this.responseCode = rCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream i, OutputStream o) {}

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    boolean isClosed() {
        return closed;
    }
}
