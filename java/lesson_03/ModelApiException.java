final class ModelApiException extends RuntimeException {
    private final int httpStatus;
    private final String responseBody;



    ModelApiException(
            String message,
            int httpStatus,
            String responseBody
    ) {
        this(message, httpStatus, responseBody, null);
    }

    ModelApiException(
            String message,
            int httpStatus,
            String responseBody,
            Throwable cause
    ) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    int httpStatus() {
        return httpStatus;
    }

    String responseBody() {
        return responseBody;
    }
}
