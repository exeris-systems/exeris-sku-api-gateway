package eu.exeris.sku.gateway.config;

/**
 * The gateway configuration is missing a required value or carries a value that cannot be used.
 *
 * <p>Raised while the configuration is read, before the kernel starts, so a misconfigured gateway
 * refuses to start instead of starting with a default the operator did not choose.
 */
public final class GatewayConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GatewayConfigException(String message) {
        super(message);
    }

    public GatewayConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
