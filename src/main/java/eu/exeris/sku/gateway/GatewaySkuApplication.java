package eu.exeris.sku.gateway;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sku.gateway.config.GatewayConfig;
import eu.exeris.sku.gateway.config.GatewayConfigException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable entry point for the Exeris API Gateway SKU (Tier 3).
 *
 * <p>Reads the {@linkplain GatewayConfig configuration}, boots the Exeris Kernel HTTP subsystem, assembles
 * the {@linkplain GatewayRuntime gateway} and serves until the process is told to stop. A configuration the
 * gateway cannot use ends the process before the kernel starts.
 *
 * <p>TLS is not provided by this SKU, in either direction: it listens in plaintext and its upstream
 * connections are plaintext, which is why only {@code http} upstreams are accepted. The client engine
 * states that requirement itself ({@code CommunityOutboundTls.PLAINTEXT}) instead of a process-wide flag,
 * because a kernel with a crypto provider bound would otherwise try TLS to a plaintext upstream.
 * Terminate TLS in front of the gateway.
 */
public final class GatewaySkuApplication {

    private static final System.Logger LOG = System.getLogger(GatewaySkuApplication.class.getName());

    /** Exit status for a configuration that cannot be used (BSD sysexits EX_CONFIG). */
    static final int EXIT_CONFIG = 78;

    private GatewaySkuApplication() {
    }

    public static void main(String[] args) throws Exception {
        GatewayConfig config;
        try {
            config = GatewayConfig.load();
        } catch (GatewayConfigException e) {
            LOG.log(System.Logger.Level.ERROR, "Gateway configuration refused, not starting: {0}", e.getMessage());
            System.exit(EXIT_CONFIG);
            return;
        }
        run(config);
    }

    /**
     * The kernel properties the configuration maps to: the listener and the request limits of the
     * kernel's HTTP server. Nothing here turns TLS on or off; the kernel's own default applies.
     *
     * @param config the validated configuration
     * @return property name to value, in the form the kernel's Community configuration reads
     */
    static Map<String, String> kernelProperties(GatewayConfig config) {
        GatewayConfig.Http http = config.http();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("exeris.http.mode", "SERVER");
        properties.put("exeris.http.bindHost", http.bindHost());
        properties.put("exeris.http.port", String.valueOf(http.port()));
        properties.put("exeris.http.maxRequestBodyBytes", String.valueOf(http.maxRequestBodyBytes()));
        properties.put("exeris.http.maxRequestHeaderCount", String.valueOf(http.maxRequestHeaderCount()));
        properties.put("exeris.http.maxRequestHeaderSize", String.valueOf(http.maxRequestHeaderSize()));
        return properties;
    }

    /**
     * The configuration of the client engine that carries requests to the upstreams.
     *
     * <p>{@code idleTimeoutMillis} is the kernel's transport idle limit: an upstream connection that moves
     * no bytes for that long is reclaimed and the request fails. It is not a deadline on the whole exchange.
     *
     * @param config the validated configuration
     * @return the client engine configuration
     */
    static HttpConfig clientConfig(GatewayConfig config) {
        GatewayConfig.Upstream upstream = config.upstream();
        return new HttpConfig(
                HttpMode.CLIENT,
                null,
                -1,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                upstream.timeoutMillis(),
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                config.http().maxRequestBodyBytes(),
                false,
                HttpVersion.HTTP_1_1,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE,
                upstream.maxResponseBytes());
    }

    /**
     * The configuration of the management listener: small, HTTP/1.1 only, and bound where the operator
     * said, which is loopback unless they chose otherwise.
     *
     * @param management the management settings
     * @return the server engine configuration
     */
    static HttpConfig managementConfig(GatewayConfig.Management management) {
        return new HttpConfig(
                HttpMode.SERVER,
                management.bindHost(),
                management.port(),
                64,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                1024,
                false,
                HttpVersion.HTTP_1_1,
                null,
                HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE,
                HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE,
                HttpConfig.DEFAULT_MAX_RESPONSE_BODY_BYTES);
    }

    private static void startManagement(GatewayRuntime gateway, GatewayConfig config,
                                        AtomicReference<HttpServerEngine> holder) {
        if (config.management() == null) {
            return;
        }
        HttpServerEngine engine = HttpKernelProviders.HTTP_PROVIDER.get()
                .createServerEngine(managementConfig(config.management()));
        engine.setHandler(gateway.management());
        engine.start();
        holder.set(engine);
        LOG.log(System.Logger.Level.INFO, "Management listener on {0}:{1}",
                config.management().bindHost(), config.management().port());
    }

    static void run(GatewayConfig config) {
        kernelProperties(config).forEach(System::setProperty);

        GatewayRuntime gateway = GatewayRuntime.create(config);
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        AtomicReference<HttpClientEngine> clientEngine = new AtomicReference<>();
        AtomicReference<HttpServerEngine> managementEngine = new AtomicReference<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.log(System.Logger.Level.INFO, "Shutdown signal received, draining gateway");
            try {
                gateway.module().drain(Duration.ofSeconds(15));
                gateway.module().terminate();
                HttpServerEngine management = managementEngine.get();
                if (management != null) {
                    management.stop();
                    management.close();
                }
                HttpClientEngine engine = clientEngine.get();
                if (engine != null) {
                    engine.close();
                }
                gateway.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Shutdown did not complete cleanly: {0}", e.getClass().getName());
            }
            shutdownLatch.countDown();
        }, "gateway-shutdown"));

        LOG.log(System.Logger.Level.INFO, "Booting Exeris Kernel subsystems, {0} route(s)", config.routes().size());
        ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, gateway.ingress())
                .run(() -> boot(gateway, config, clientEngine, managementEngine, shutdownLatch));
    }

    private static void boot(GatewayRuntime gateway, GatewayConfig config,
                             AtomicReference<HttpClientEngine> clientEngine, AtomicReference<HttpServerEngine> managementEngine,
                             CountDownLatch shutdownLatch) {
        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http", "transport"))
                    .build()
                    .boot(() -> {
                        try {
                            HttpClientEngine engine = new CommunityHttpProvider()
                                    .createClientEngine(clientConfig(config), CommunityOutboundTls.PLAINTEXT);
                            engine.start();
                            clientEngine.set(engine);

                            gateway.attach(engine);
                            startManagement(gateway, config, managementEngine);
                            gateway.module().initialize();
                            gateway.module().ready();
                            LOG.log(System.Logger.Level.INFO, "Exeris API Gateway running on {0}:{1}",
                                    config.http().bindHost(), config.http().port());
                            shutdownLatch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            throw new IllegalStateException("Gateway lifecycle failed", e);
                        }
                    });
        } catch (KernelBootstrap.BootstrapException e) {
            throw new IllegalStateException("Kernel boot failed", e);
        }
    }
}
