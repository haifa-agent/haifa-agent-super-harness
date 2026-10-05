package io.haifa.agent.superharness.server.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Adapter that presents a Javalin request/response as a JDK {@link HttpExchange}.
 * Allows frozen, battle-tested DeerFlow parity handlers to run transparently on Javalin.
 */
public final class JavalinHttpExchange extends HttpExchange {
    private final Context ctx;
    private final HttpServletRequest req;
    private final HttpServletResponse res;
    private final Headers requestHeaders;
    private final Headers responseHeaders;
    private final URI requestUri;
    private int responseCode = -1;
    private boolean headersSent = false;
    private OutputStream responseBodyStream;

    public JavalinHttpExchange(Context ctx) {
        this.ctx = ctx;
        this.req = ctx.req();
        this.res = ctx.res();
        this.requestHeaders = new Headers();
        this.responseHeaders = new Headers();

        Enumeration<String> headerNames = req.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                Enumeration<String> values = req.getHeaders(name);
                while (values != null && values.hasMoreElements()) {
                    requestHeaders.add(name, values.nextElement());
                }
            }
        }

        String rawQuery = req.getQueryString();
        String uriStr = req.getRequestURI() + (rawQuery != null ? "?" + rawQuery : "");
        this.requestUri = URI.create(uriStr);
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
        return requestUri;
    }

    @Override
    public String getRequestMethod() {
        return req.getMethod();
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        if (!headersSent) {
            try {
                sendResponseHeaders(responseCode > 0 ? responseCode : 200, -1);
            } catch (IOException ignored) {}
        }
        if (responseBodyStream != null) {
            try {
                responseBodyStream.flush();
                responseBodyStream.close();
            } catch (IOException ignored) {}
        }
    }

    @Override
    public InputStream getRequestBody() {
        try {
            return req.getInputStream();
        } catch (IOException e) {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    @Override
    public OutputStream getResponseBody() {
        if (responseBodyStream == null) {
            try {
                responseBodyStream = res.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return responseBodyStream;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        if (headersSent) {
            return;
        }
        this.responseCode = rCode;
        this.headersSent = true;
        res.setStatus(rCode);
        for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
            String name = entry.getKey();
            for (String val : entry.getValue()) {
                res.addHeader(name, val);
            }
        }
        if (responseLength > 0) {
            res.setContentLengthLong(responseLength);
        }
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        try {
            String addrStr = req.getRemoteAddr();
            InetAddress addr = InetAddress.getByName(addrStr != null ? addrStr : "127.0.0.1");
            return new InetSocketAddress(addr, req.getRemotePort());
        } catch (Exception e) {
            return new InetSocketAddress("127.0.0.1", req.getRemotePort());
        }
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        try {
            String addrStr = req.getLocalAddr();
            InetAddress addr = InetAddress.getByName(addrStr != null ? addrStr : "127.0.0.1");
            return new InetSocketAddress(addr, req.getLocalPort());
        } catch (Exception e) {
            return new InetSocketAddress("127.0.0.1", req.getLocalPort());
        }
    }

    @Override
    public String getProtocol() {
        return req.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
        return req.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        req.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        // Unused in standard HttpServer pipeline
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}
