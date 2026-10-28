package io.haifa.agent.superharness.server.integrations.lark;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Package-private helper to enforce an overall transfer deadline (encompassing both headers and
 * the full response body) for Java 21 {@link HttpClient} requests, cancelling the underlying
 * transport future on timeout or interruption.
 */
final class LarkHttpCalls {
    private LarkHttpCalls() {}

    static <T> HttpResponse<T> sendWithDeadline(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseHandler,
            Duration overallDeadline) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, responseHandler);
        try {
            return future.get(Math.max(1, overallDeadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            HttpTimeoutException timeoutException = new HttpTimeoutException(
                    "HTTP transfer timed out after " + overallDeadline.toMillis() + "ms");
            timeoutException.initCause(timeout);
            throw timeoutException;
        } catch (ExecutionException execution) {
            future.cancel(true);
            Throwable cause = execution.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof InterruptedException) {
                throw (InterruptedException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("HTTP transfer failed: " + cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        }
    }
}
