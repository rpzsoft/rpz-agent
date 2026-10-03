package com.rpzsoft.agent.ai;

/**
 * Model call failure with a retryability classification: connection errors,
 * timeouts, 408/429 and 5xx are retried; request/config errors (400/401/403)
 * are not.
 */
public class ModelException extends RuntimeException {

    private final int status;
    private final boolean retryable;

    public ModelException(String message, int status, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
    }

    public int getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static ModelException http(int status, String message) {
        boolean retryable = status == 408 || status == 429 || status >= 500;
        return new ModelException(message, status, retryable, null);
    }

    public static ModelException connection(Throwable cause) {
        return new ModelException(cause == null ? "connection failed" : cause.getMessage(),
                -1, true, cause);
    }

    public static ModelException timeout() {
        return new ModelException("model request timed out", -1, true, null);
    }
}
