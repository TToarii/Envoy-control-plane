package com.example.demogrpcserver.services;

import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.grpc.*;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnvoyDiscoveryServer {

    private final ConfigurationLoader configLoader;
    private final DiscoveryServerCallback callback;


    // @Value("${nc.discovery.server.port:18000}")
    private int discoveryServerPort = 18000;

    // @Value("${nc.discovery.server.grpc.channel-timeout:180000}")
    private int grpcChannelTimeout = 180000;


    private SimpleCacheWrapper<String> cache = null;
    private Server grpcServer = null;
    V3DiscoveryServer discoveryServer;



    /**
     * Create gRPC server for Discovery server
     */
    @PostConstruct
    private void init() {

        log.info("Try to build GRPC server");

        File serverCaFile = new File("/private/tmp/grpc-envoy/certs/ca-cert.pem");
        File serverCertFile = new File("/private/tmp/grpc-envoy/certs/server-cert.pem");
        File serverKeyFile = new File("/private/tmp/grpc-envoy/certs/server-key.pem");

        if (!serverCertFile.exists() || !serverKeyFile.exists()) {
            throw new RuntimeException("Can't read server cert and/or key file for mTLS");
        }

        try {
            // Envoy node cache
            cache = configLoader.getCache();

            // V3 xDS API server
            this.discoveryServer = new V3DiscoveryServer(callback, cache);

            Thread grpcServerThread = new Thread(() -> {

                log.debug("Try to start GRPC in thread");

                // Create Netty server
                NettyServerBuilder nettyServerBuilder = NettyServerBuilder.forPort(discoveryServerPort);

                try {
                    SslContext sslContext = GrpcSslContexts.forServer(
                                    serverCertFile,
                                    serverKeyFile
                            )
                            .trustManager(serverCaFile)
                            .clientAuth(ClientAuth.REQUIRE)
                            .build();

                    // nettyServerBuilder.sslContext(sslContext);

                } catch (SSLException e) {
                    throw new RuntimeException("Can't build ssl context for GRPC server for mTLS", e);
                }

                nettyServerBuilder.addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
                        // .addService(discoveryServer.getClusterDiscoveryServiceImpl())
                        // .addService(discoveryServer.getListenerDiscoveryServiceImpl())
                        // .addService(discoveryServer.getEndpointDiscoveryServiceImpl())
                        // .addService(discoveryServer.getRouteDiscoveryServiceImpl())
                        // .addService(discoveryServer.getSecretDiscoveryServiceImpl())

                        .withChildOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, grpcChannelTimeout)

                        // interceptor
                        .intercept(new ServerInterceptor() {
                            @Override
                            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                    ServerCall<ReqT, RespT> call,
                                    Metadata headers,
                                    ServerCallHandler<ReqT, RespT> next) {

                                log.debug("interceptor - call: [{}], header: [{}]", call.getAttributes(), headers);
                                return next.startCall(call, headers);
                            }
                        });
                grpcServer = nettyServerBuilder.build();

                try {
                    grpcServer.start();

                    log.info("V3DiscoveryServer has started on port {} for GRPC", grpcServer.getPort());

                    grpcServer.awaitTermination();

                    log.info("GRPC Server terminated.");
                } catch(IOException e) {
                    throw new InternalError(e.getMessage(), e);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "GRPC Server Thread");

            log.info("Thread for GRPC {}", grpcServerThread.getId());

            grpcServerThread.start();
        } catch(Exception e) {
            throw new InternalError(e.getMessage(), e);
        }

        log.debug("After try to create GRPC server");

    }

    protected SimpleCacheWrapper<String> getCache() {
        return cache;
    }

    public Server getGrpcServer() {

        return this.grpcServer;
    }

    public V3DiscoveryServer getServer() {

        return this.discoveryServer;
    }

    @PreDestroy
    private void shutdown() {
        log.info("Discovery Server destroyed.");

        if(grpcServer != null) {
            log.debug("GRPC Server shutting down...");
            grpcServer.shutdown();
        }
    }


}
