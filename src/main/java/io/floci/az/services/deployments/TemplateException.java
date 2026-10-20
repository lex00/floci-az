package io.floci.az.services.deployments;

/** A template that cannot be deployed, with the ARM error code that says why. */
final class TemplateException extends RuntimeException {

    private final String code;

    TemplateException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
