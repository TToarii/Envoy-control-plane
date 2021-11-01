package com.example.demogrpcserver.controllers;

import com.example.demogrpcserver.helpers.EnvoyHelpers;
import com.example.demogrpcserver.services.ConfigurationLoader;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ControlPlane;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Working Rest Envoy controller for Xds but no longer work on this, use GRPC instead
@Slf4j
@RestController
@RequiredArgsConstructor
public class DiscoverRestController {

    public final static String DISCOVERY_SERVER_UNIQUE_ID = UUID.randomUUID().toString();

    @PostMapping(path = "/v3/discovery:clusters")
    public ResponseEntity<String> discoveryClusters(
            @RequestBody Object discoveryRequest
    ) throws InvalidProtocolBufferException {

        log.info("Unique discovery server id : {}", DiscoverRestController.DISCOVERY_SERVER_UNIQUE_ID);

        log.info("REST Discovery for Clusters -> {}", discoveryRequest);


        Cluster clusterWhoami1 = EnvoyHelpers.createCluster("whoami_cluster_test_01", "whoami", 80);
        Cluster clusterWhoami2 = EnvoyHelpers.createCluster("whoami_cluster_test_02", "whoami", 80);


        DiscoveryResponse discoveryResponse = DiscoveryResponse.newBuilder()
                .setControlPlane(
                        ControlPlane.newBuilder()
                                .setIdentifier(DiscoverRestController.DISCOVERY_SERVER_UNIQUE_ID)
                                .build()
                )
                .setTypeUrl("type.googleapis.com/envoy.config.cluster.v3.Cluster")
                .addResources(
                        Any.newBuilder()
                                .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                .setValue(clusterWhoami1.toByteString())
                                .build()
                )
                .addResources(
                        Any.newBuilder()
                                .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
                                .setValue(clusterWhoami2.toByteString())
                                .build()
                )
                .build();


        JsonFormat.TypeRegistry build = JsonFormat.TypeRegistry
                .newBuilder()
                .add(
                        Cluster.getDescriptor()
                )
                .build();

        return ResponseEntity.ok(
                JsonFormat.printer().usingTypeRegistry(build).print(discoveryResponse)
        );
    }



    @PostMapping(path = "/v3/discovery:listeners")
    public ResponseEntity<Object> discoveryListeners(
            @RequestBody Object discoveryRequest
    ) {

        log.info("REST Discovery for Listeners");





        return ResponseEntity.ok().build();
    }


}
