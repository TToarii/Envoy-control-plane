package com.example.demogrpcserver.controllers;

import com.example.demogrpcserver.helpers.EnvoyHelpers;
import com.example.demogrpcserver.services.ConfigurationLoader;
import com.example.demogrpcserver.services.EnvoyDiscoveryServer;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.net.SocketAddress;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/grpc")
public class GrpcManagementRestController {


    private final ConfigurationLoader configurationLoader;
    private final EnvoyDiscoveryServer envoyDiscoveryServer;



    // ##### GRPC SERVER #####
    // ##### GRPC SERVER #####
    // ##### GRPC SERVER #####
    @GetMapping(path = "/server/info")
    public ResponseEntity<Object> getGrpcInfo() {

        List<? extends SocketAddress> listenSockets = this.envoyDiscoveryServer
                .getGrpcServer()
                .getListenSockets();

        return ResponseEntity.ok().build();
    }
    // ##### GRPC SERVER #####
    // ##### GRPC SERVER #####
    // ##### GRPC SERVER #####



    // ##### CLUSTERS #####
    // ##### CLUSTERS #####
    // ##### CLUSTERS #####
    @PostMapping(path = "/clusters")
    public ResponseEntity<Object> addCluster(
            @RequestBody ClusterDto dto
    ) {

        log.info("POST new cluster");

        Cluster cluster = EnvoyHelpers.createCluster(
                dto.getName(),
                dto.getAddress(),
                dto.getPort()
        );


        this.configurationLoader.addCluster(cluster);

        return ResponseEntity.ok(dto);
    }
    // ##### CLUSTERS #####
    // ##### CLUSTERS #####
    // ##### CLUSTERS #####



    // ##### LISTENERS #####
    // ##### LISTENERS #####
    // ##### LISTENERS #####
    @GetMapping(path = "/listeners")
    public ResponseEntity<Object> getListeners() throws InvalidProtocolBufferException {

        Snapshot snapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        JsonFormat.TypeRegistry build = JsonFormat.TypeRegistry
                .newBuilder()
                .add(
                        Arrays.asList(
                                Listener.getDescriptor(),
                                HttpConnectionManager.getDescriptor()
                        )
                )
                .build();

        List<String> response = new ArrayList<>();
        for (Map.Entry<String, Listener> kv : snapshot.listeners().resources().entrySet()) {

            response.add(JsonFormat.printer().usingTypeRegistry(build).print(kv.getValue()));
        }

        return ResponseEntity.ok(
                response
        );
    }


    @PostMapping(path = "/listeners")
    public ResponseEntity<Object> addListener(
            @RequestBody ListenerDto dto
    ) {

        log.info("POST new listener");

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (defaultGroupCacheSnapshot.listeners().resources().containsKey(dto.getName())) {

            throw new RuntimeException("Listener with this name already exist");
        }

        Set<EnvoyHelpers.PairDomainClusterName> kv = new HashSet<>();
        for (PairDomainClusterNameDto pairDomainClusterNameDto : dto.getPairDomainClusterName()) {
            kv.add(
                    EnvoyHelpers.PairDomainClusterName
                            .builder()
                            .domain(pairDomainClusterNameDto.getDomain())
                            .clusterName(pairDomainClusterNameDto.getClusterName())
                            .build()
            );
        }

        Listener listener = EnvoyHelpers.createListener(
                dto.getName(),
                dto.getAddress(),
                dto.getPort(),
                kv
        );

        this.configurationLoader.addListener(listener);

        return ResponseEntity.ok(dto);
    }

    @PutMapping(path = "/listeners/{name}")
    public ResponseEntity<Object> editListener(
            @PathVariable("name") String name,
            @RequestBody ListenerDto dto
    ) {

        log.info("PUT listener for name {}", name);

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (!defaultGroupCacheSnapshot.listeners().resources().containsKey(name)) {

            throw new RuntimeException("Listener not found with name " + name);
        }

        Listener listener = defaultGroupCacheSnapshot.listeners().resources().get(name);

        Listener.Builder builder = Listener.newBuilder(listener);
        builder = builder
                .setName(dto.getName())
                .setAddress(
                        EnvoyHelpers.createAddress(
                                dto.getAddress(),
                                dto.getPort()
                        )
                );

        builder = builder.clearFilterChains();

        for (PairDomainClusterNameDto kv : dto.getPairDomainClusterName()) {

            builder = builder.addFilterChains(
                    EnvoyHelpers.createFilterChainForDomain(
                            kv.getDomain(),
                            kv.getClusterName()
                    )
            );

        }


        this.configurationLoader.updateListenerDbData(name, builder.build());

        return ResponseEntity.ok(dto);
    }


    @DeleteMapping("/listeners/{name}")
    public ResponseEntity<Object> deleteListener(
            @PathVariable("name") String name
    ) {

        log.info("DELETE listener with name {}", name);

        this.configurationLoader.removeListenerDbDataAndReloadConfig(name);

        return ResponseEntity.ok().build();
    }

    @PutMapping(path = "/dynamics-listeners/{name}")
    public ResponseEntity<Object> replaceDynamicListener(
            @PathVariable("name") String name,
            @RequestBody DynamicListener dto
    ) {

        log.info("PUT dynamic listener with name {}", name);

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (!defaultGroupCacheSnapshot.listeners().resources().containsKey(name)) {

            throw new RuntimeException("Listener not found with name " + name);
        }

        // Listener listener = defaultGroupCacheSnapshot.listeners().resources().get(name);

        Listener listenerUpdated = EnvoyHelpers.createDynamicListener(
                dto.getName(),
                dto.getAddress(),
                dto.getPort(),
                dto.getHttpConfigs()
        );

        this.configurationLoader.updateListenerDbData(name, listenerUpdated);

        return ResponseEntity.ok(dto);
    }

    @PatchMapping(path = "/dynamics-listeners/{name}")
    public ResponseEntity<Object> editDynamicListener(
            @PathVariable("name") String name,
            @RequestBody HttpConfigDto dto
    ) {

        log.info("PATCH dynamic listener with name {}", name);

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (!defaultGroupCacheSnapshot.listeners().resources().containsKey(name)) {

            throw new RuntimeException("Listener not found with name " + name);
        }

        Listener listener = defaultGroupCacheSnapshot.listeners().resources().get(name);

        Listener listenerUpdated = EnvoyHelpers.updateDynamicListener(
                listener,
                dto.getHttpConfigs()
        );

        this.configurationLoader.updateListenerDbData(name, listenerUpdated);

        return ResponseEntity.ok(dto);
    }
    // ##### LISTENERS #####
    // ##### LISTENERS #####
    // ##### LISTENERS #####



    // ##### ROUTES #####
    // ##### ROUTES #####
    // ##### ROUTES #####
    @PostMapping("/routes")
    public ResponseEntity<Object> createRoute(
            @RequestBody @Valid RouteDto dto
    ) {

        log.info("POST new route");

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (defaultGroupCacheSnapshot.routes().resources().containsKey(dto.getName())) {

            throw new RuntimeException("Route with this name already exist");
        }

        Set<EnvoyHelpers.TripleDomainNameFilterDomainNameClusterName> routeData = new HashSet<>(1);
        for (TripleDomainNameFilterDomainNameClusterNameDto data : dto.getData()) {

            routeData.add(
                    EnvoyHelpers.TripleDomainNameFilterDomainNameClusterName.tripleBuilder()
                            .domainNameFilter(data.getDomainNameFilter())
                            .domain(data.getDomain())
                            .clusterName(data.getClusterName())
                            .tripleBuild()
            );

        }

        RouteConfiguration routeConfiguration = EnvoyHelpers.createRouteConfiguration(
                dto.getName(),
                routeData
        );

        this.configurationLoader.addRoute(routeConfiguration);

        return ResponseEntity.ok(dto);
    }

    @PutMapping("/routes/{name}")
    public ResponseEntity<Object> updateRoute(
            @PathVariable("name") String name,
            @RequestBody @Valid RouteDto dto
    ) {

        log.info("Update route with name {}", name);

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (!defaultGroupCacheSnapshot.routes().resources().containsKey(name)) {

            throw new RuntimeException("Route not found with name " + name);
        }

        RouteConfiguration route = defaultGroupCacheSnapshot.routes().resources().get(name);

        Set<EnvoyHelpers.TripleDomainNameFilterDomainNameClusterName> routeData = new HashSet<>(1);
        for (TripleDomainNameFilterDomainNameClusterNameDto data : dto.getData()) {

            routeData.add(
                    EnvoyHelpers.TripleDomainNameFilterDomainNameClusterName.tripleBuilder()
                            .domainNameFilter(data.getDomainNameFilter())
                            .domain(data.getDomain())
                            .clusterName(data.getClusterName())
                            .tripleBuild()
            );

        }

        RouteConfiguration routeConfiguration = EnvoyHelpers.createRouteConfiguration(
                dto.getName(),
                routeData
        );


        this.configurationLoader.updateRouteConfigurationDbDataAndReloadConfig(name, routeConfiguration);

        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/routes/{name}")
    public ResponseEntity<Object> deleteRoute(
            @PathVariable("name") String name
    ) {

        log.info("DELETE route with name {}", name);

        this.configurationLoader.removeRouteConfigurationDbDataAndReloadConfig(name);

        return ResponseEntity.ok("OK");
    }
    // ##### ROUTES #####
    // ##### ROUTES #####
    // ##### ROUTES #####



    // ##### SECRETS #####
    // ##### SECRETS #####
    // ##### SECRETS #####
    @PostMapping("/secrets")
    public ResponseEntity<Object> createSecret(
            @RequestBody @Valid SecretDto dto
    ) {

        log.info("POST new secret");

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (defaultGroupCacheSnapshot.secrets().resources().containsKey(dto.getName())) {

            throw new RuntimeException("Route with this name already exist");
        }

        Secret tlsSecret = EnvoyHelpers.createTlsSecret(
                dto.getName(), // Match Listener SDS config name
                dto.getCertificateChain(),
                dto.getPrivateKey()
        );

        this.configurationLoader.addSecret(tlsSecret);

        return ResponseEntity.ok(dto);
    }

    @PutMapping("/secrets/{name}")
    public ResponseEntity<Object> updateSecret(
            @PathVariable("name") String name,
            @RequestBody @Valid SecretDto dto
    ) {

        log.info("Update secret with name {}", name);

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        if (!defaultGroupCacheSnapshot.routes().resources().containsKey(name)) {

            throw new RuntimeException("Secret not found with name " + name);
        }

        Secret tlsSecret = EnvoyHelpers.createTlsSecret(
                dto.getName(), // Match Listener SDS config name
                dto.getCertificateChain(),
                dto.getPrivateKey()
        );

        this.configurationLoader.updateSecretDbDataAndReloadConfig(name, tlsSecret);

        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/secrets/{name}")
    public ResponseEntity<Object> deleteSecret(
            @PathVariable("name") String name
    ) {

        log.info("DELETE secret with name {}", name);

        this.configurationLoader.removeSecretDbDataAndReloadConfig(name);

        return ResponseEntity.ok("OK");
    }
    // ##### SECRETS #####
    // ##### SECRETS #####
    // ##### SECRETS #####



    @GetMapping(path = "discovery-server")
    public ResponseEntity<Object> getDiscoveryServer() {

        V3DiscoveryServer server = this.envoyDiscoveryServer.getServer();

        return ResponseEntity.ok("OK");
    }

    @GetMapping(path = "all-configurations-groups")
    public ResponseEntity<Object> getAllConfigurationGroups() {

        return ResponseEntity.ok(
                this.configurationLoader.getCache().groups()
        );
    }

    @GetMapping(path = "default-configuration-resources")
    public ResponseEntity<Object> getDefaultConfigurationResources() {

        Snapshot defaultGroupCacheSnapshot = this.configurationLoader.getDefaultGroupCacheSnapshot();

        return ResponseEntity.ok().build();
    }



    @Data
    public static class ClusterDto {

        @NotBlank
        private String name;

        @NotBlank
        private String address;

        private int port = 8080;

    }

    @Data
    public static class ListenerDto {

        @NotBlank
        private String name;

        @NotBlank
        private String address;

        private int port = 80;

        @NotNull
        private Set<PairDomainClusterNameDto> pairDomainClusterName = new HashSet<>();

    }

    @Data
    public static class PairDomainClusterNameDto {

        @NotBlank
        private String domain;

        @NotBlank
        private String clusterName;

    }

    @Data
    public static class RouteDto {

        @NotBlank
        private String name;

        @Valid
        @NotEmpty
        private Set<TripleDomainNameFilterDomainNameClusterNameDto> data;

    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class TripleDomainNameFilterDomainNameClusterNameDto extends PairDomainClusterNameDto {

        @NotBlank
        private String domainNameFilter = "*";
    }

    @Data
    public static class SecretDto {

        @NotBlank
        private String name;

        @NotBlank
        private String certificateChain;

        @NotBlank
        private String privateKey;

    }

    @Data
    public static class HttpConfigDto {

        @Valid
        @NotEmpty
        Set<EnvoyHelpers.HttpConfig> httpConfigs = new HashSet<>(1);

    }

    @Data
    public static class DynamicListener {

        @NotBlank
        private String name;

        @NotBlank
        private String address;

        private int port = 80;

        @Valid
        @NotEmpty
        Set<EnvoyHelpers.HttpConfig> httpConfigs = new HashSet<>(1);

    }

}
