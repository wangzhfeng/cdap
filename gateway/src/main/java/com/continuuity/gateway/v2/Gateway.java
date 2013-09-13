package com.continuuity.gateway.v2;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.http.core.HttpHandler;
import com.continuuity.common.http.core.NettyHttpService;
import com.continuuity.weave.common.Cancellable;
import com.continuuity.weave.discovery.Discoverable;
import com.continuuity.weave.discovery.DiscoveryService;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Gateway implemented using the common http netty framework.
 */
public class Gateway extends AbstractIdleService {
  private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

  private final CConfiguration cConf;
  private final NettyHttpService httpService;
  private final DiscoveryService discoveryService;
  private Cancellable cancelDiscovery;

  @Inject
  public Gateway(CConfiguration cConf,
                 @Named(Constants.Gateway.ADDRESS) InetAddress hostname,
                 @Named(Constants.Gateway.GATEWAY_V2_HTTP_HANDLERS) Set<HttpHandler> handlers,
                 DiscoveryService discoveryService) {

    this.cConf = cConf;

    NettyHttpService.Builder builder = NettyHttpService.builder();
    builder.addHttpHandlers(handlers);
    builder.setHost(hostname.getCanonicalHostName());
    builder.setPort(cConf.getInt(Constants.Gateway.PORT, Constants.Gateway.DEFAULT_PORT));
    builder.setConnectionBacklog(cConf.getInt(Constants.Gateway.BACKLOG, Constants.Gateway.DEFAULT_BACKLOG));
    builder.setExecThreadPoolSize(cConf.getInt(Constants.Gateway.EXEC_THREADS,
                                               Constants.Gateway.DEFAULT_EXEC_THREADS));
    builder.setBossThreadPoolSize(cConf.getInt(Constants.Gateway.BOSS_THREADS,
                                               Constants.Gateway.DEFAULT_BOSS_THREADS));
    builder.setWorkerThreadPoolSize(cConf.getInt(Constants.Gateway.WORKER_THREADS,
                                                 Constants.Gateway.DEFAULT_WORKER_THREADS));

    this.httpService = builder.build();
    this.discoveryService = discoveryService;
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting Gateway...");
    httpService.startAndWait();

    // Register the service
    cancelDiscovery = discoveryService.register(new Discoverable() {
      @Override
      public String getName() {
        return cConf.get(Constants.Gateway.SERVICE_NAME, Constants.Gateway.DEFAULT_SERVICE_NAME);
      }

      @Override
      public InetSocketAddress getSocketAddress() {
        return httpService.getBindAddress();
      }
    });

    LOG.info("Gateway started successfully on {}", httpService.getBindAddress());
  }

  @Override
  protected void shutDown() throws Exception {
    LOG.info("Stopping Gateway...");

    // Unregister the service
    cancelDiscovery.cancel();
    // Wait for a few seconds for requests to stop
    try {
      TimeUnit.SECONDS.sleep(8);
    } catch (InterruptedException e) {
      LOG.error("Interrupted while waiting...", e);
    }

    httpService.stopAndWait();
  }

  public InetSocketAddress getBindAddress() {
    return httpService.getBindAddress();
  }
}
