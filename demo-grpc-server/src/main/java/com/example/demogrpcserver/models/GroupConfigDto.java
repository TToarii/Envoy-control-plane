package com.example.demogrpcserver.models;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupConfigDto {

    private String name;


    @Builder.Default
    private Set<Listener> listenerList = new HashSet<>(0);

    @Builder.Default
    private Set<Cluster> clusterList = new HashSet<>(0);

    @Builder.Default
    private Set<RouteConfiguration> routeList = new HashSet<>(0);

    @Builder.Default
    private Set<ClusterLoadAssignment> endpointList = new HashSet<>(0);

    @Builder.Default
    private Set<Secret> secretList = new HashSet<>(0);

}
