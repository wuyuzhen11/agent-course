package com.example.agent.lesson02;

public final class ModelApiException extends RuntimeException {
    private final int httpStatus;
    private final String responseBody;

    public ModelApiException(String message, int httpStatus, String responseBody) {
        this(message, httpStatus, responseBody, null);
    }

    public ModelApiException(String message, int httpStatus, String responseBody, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String responseBody() {
        return responseBody;
    }
}
