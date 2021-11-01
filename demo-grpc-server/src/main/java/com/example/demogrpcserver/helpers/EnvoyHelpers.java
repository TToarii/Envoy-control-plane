package com.example.demogrpcserver.helpers;

import com.example.demogrpcserver.services.ConfigurationLoader;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.*;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.*;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public abstract class EnvoyHelpers {

    /**
     * TODO
     * - Add option to enable proxy websocket for listener
     * - Add option to enable cache for listener
     * - Add option for compression ?
     */

    /**
     * /!\ CAUTION : 'ServerNames' here in FilterChainMatch only work with HTTPS connections
     *            If we need to map HTTP requests to a Cluster by domain, use 'domains' field in VirtualHost
     *
     * Here, if SDS config is null, we set SDS to target SDS default cert
     *
     * FilterChain contain :
     * - 1 FilterChainMatch (for configure 'ServerNames')
     * - 1 or n Filters (likely for HttpConnectionManager)
     * - 0 or n TransportSocket (for TLS connection)
     *<br>
     * HttpConnectionManager contain :
     * - 1 or n (can be 0 but .... xD) (for information about VirtualHost -> RouteConfigurations -> Match / Cluster)
     *<br>
     * TransportSocket :
     * Use with SDS config to retrieve dynamically Secrets (configure multi SDS for multi domains certs)
     *<br>
     *<br>
     * Configuration example :
     * - ServerNames: 'qa.domain.com, dev.domain.com, demo.domain.com'
     * - Need 3 Filters (HttpConnectionManager) for redirect to 'qa', 'dev', 'demo' clusters
     * - Can have 1 TransportSocket for TLS that contain a SAN Certs
     *   OR can have 3 TransportSocket for TLS that contain each a Cert for each sub-domains
     *
     */

    public static Cluster createCluster(String name, String address, int port) {

        return Cluster.newBuilder()
                .setName(name)
                .setConnectTimeout(Durations.fromSeconds(5L))
                .setType(Cluster.DiscoveryType.LOGICAL_DNS)
                .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                .setLoadAssignment(
                        ClusterLoadAssignment.newBuilder()
                                .setClusterName(name)
                                .addEndpoints(
                                        LocalityLbEndpoints.newBuilder()
                                                .addLbEndpoints(
                                                        LbEndpoint.newBuilder()
                                                                .setEndpoint(
                                                                        Endpoint.newBuilder()
                                                                                .setAddress(
                                                                                        Address.newBuilder()
                                                                                                .setSocketAddress(
                                                                                                        SocketAddress.newBuilder()
                                                                                                                .setAddress(address)
                                                                                                                .setPortValue(port)
                                                                                                                .build()
                                                                                                )
                                                                                                .build()
                                                                                )
                                                                                .build()
                                                                )
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

    }


    public static Listener createListener(String name, String address, int port, String domain, String clusterName) {

        return EnvoyHelpers.createListener(
                name,
                address,
                port,
                PairDomainClusterName.builder()
                        .domain(domain)
                        .clusterName(clusterName)
                        .build()
        );

    }

    public static Listener createListener(String name, String address, int port, PairDomainClusterName domainClusterName) {

        Set<PairDomainClusterName> domains = Collections.singleton(domainClusterName);

        return EnvoyHelpers.createListener(name, address, port, domains);

    }

    public static Listener createListener(String name, String address, int port, Set<PairDomainClusterName> domains) {

        log.info("Create new listener with name <{}>, address <{}> and port <{}>", name, address, port);

        assert !domains.isEmpty();

        Listener.Builder builder = Listener.newBuilder()
                .setName(name)
                .setAddress(
                        EnvoyHelpers.createAddress(address, port)
                );

                // .addListenerFilters(
                //         ListenerFilter.newBuilder()
                //                 .setName("envoy.filters.listener.http_inspector")
                //                 .build()
                // );

        for (PairDomainClusterName kv : domains) {
            builder = builder.addFilterChains(
                    EnvoyHelpers.createFilterChainForDomain(
                            kv.getDomain(),
                            kv.getClusterName()
                    )
            );
        }

        return builder.build();

    }


    public static Listener createListenerWithRds(String name, String address, int port, String rdsRouteConfigName) {

        HttpConnectionManager httpConnectionManager = HttpConnectionManager.newBuilder()
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStatPrefix("ingress_http_01") // TODO - FROM VAR ?

                .addAccessLog(
                        AccessLog.newBuilder()
                                .setName("envoy.access_loggers.stdout")
                                .setTypedConfig(
                                        Any.newBuilder()
                                                .setTypeUrl("type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog")
                                                .build()
                                )
                                .build()
                )

                .addHttpFilters(
                        HttpFilter.newBuilder()
                                .setName("envoy.filters.http.router")
                                .build()
                )

                // Routes with RDS
                .setRds(
                        Rds.newBuilder()
                                .setRouteConfigName(rdsRouteConfigName)
                                .setConfigSource(
                                        EnvoyHelpers.createConfigSourceWithDefaultAds()
                                )
                                .build()
                )

                .build();


        Filter filter = Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(
                        Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                .setValue(httpConnectionManager.toByteString())
                                .build()
                )
                .build();


        FilterChain filterChain = FilterChain.newBuilder()
                // .setName(domain)
                .addFilters(filter)
                .build();

        return Listener.newBuilder()
                .setName(name)
                .setAddress(
                        EnvoyHelpers.createAddress(address, port)
                )
                .addFilterChains(
                        filterChain
                )
                .build();

    }

    // TODO - Refacto / rework
    public static Listener createListenerWithRdsAndSds(String name, String address, int port, String rdsRouteConfigName, String sdsSecretConfigName) {

        // FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
        //         .addServerNames(domain) // SNI domain
        //         .build();

        HttpConnectionManager httpConnectionManager = HttpConnectionManager.newBuilder()
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStatPrefix("ingress_http_01") // TODO - FROM VAR ?

                .addAccessLog(
                        AccessLog.newBuilder()
                                .setName("envoy.access_loggers.stdout")
                                .setTypedConfig(
                                        Any.newBuilder()
                                                .setTypeUrl("type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog")
                                                .build()
                                )
                                .build()
                )

                .addHttpFilters(
                        HttpFilter.newBuilder()
                                .setName("envoy.filters.http.router")
                                .build()
                )

                // Routes with RDS
                .setRds(
                        Rds.newBuilder()
                                .setRouteConfigName(rdsRouteConfigName)
                                .setConfigSource(
                                        ConfigSource.newBuilder()
                                                .setResourceApiVersion(
                                                        ApiVersion.V3
                                                )
                                                .setAds(
                                                        AggregatedConfigSource.getDefaultInstance() // Empty here for 'ads: {}' config
                                                )
                                                .build()
                                )
                                .build()
                )

                .build();


        Filter filter = Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(
                        Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                .setValue(httpConnectionManager.toByteString())
                                .build()
                )
                .build();


        // TLS with SDS
        CommonTlsContext commonTlsContext = CommonTlsContext.newBuilder()
                .addTlsCertificateSdsSecretConfigs(
                        SdsSecretConfig.newBuilder()
                                .setName(sdsSecretConfigName)
                                .setSdsConfig(
                                        EnvoyHelpers.createConfigSourceWithDefaultAds()
                                )
                                .build()
                )
                .build();


        FilterChain filterChain = FilterChain.newBuilder()
                // .setName(domain)
                //.setFilterChainMatch(filterChainMatch)

                // TLS
                .setTransportSocket(
                        TransportSocket.newBuilder()
                                .setName("envoy.transport_sockets.tls") // Envoy specific name
                                .setTypedConfig(
                                        Any.newBuilder()
                                                .setTypeUrl("type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext")
                                                .setValue(commonTlsContext.toByteString())
                                                .build()
                                )
                                .build()
                )

                .addFilters(filter)
                .build();

        return Listener.newBuilder()
                .setName(name)
                .setAddress(
                        EnvoyHelpers.createAddress(address, port)
                )
                .addFilterChains(
                        filterChain
                )
                .build();

    }


    public static FilterChain createFilterChainForDomain(String domain, String clusterName) {

        FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
                .addServerNames(domain) // SNI domain
                .build();

        HttpConnectionManager httpConnectionManager = HttpConnectionManager.newBuilder()
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStatPrefix("ingress_http_01") // TODO - FROM VAR ?

                .addAccessLog(
                        AccessLog.newBuilder()
                                .setName("envoy.access_loggers.stdout")
                                .setTypedConfig(
                                        Any.newBuilder()
                                                .setTypeUrl("type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog")
                                                .build()
                                )
                                .build()
                )

                .addHttpFilters(
                        HttpFilter.newBuilder()
                                .setName("envoy.filters.http.router")
                                .build()
                )

                .setRouteConfig(
                        EnvoyHelpers.createRouteConfiguration(domain, clusterName)
                )

                .build();


        Filter filter = Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(
                        Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                .setValue(httpConnectionManager.toByteString())
                                .build()
                )
                .build();


        return FilterChain.newBuilder()
                .setName(domain)
                .setFilterChainMatch(filterChainMatch)
                .addFilters(filter)
                .build();

    }


    public static RouteConfiguration createRouteConfiguration(String domain, String clusterName) {

        return EnvoyHelpers.createRouteConfiguration("local_route", domain, clusterName);
    }

    public static RouteConfiguration createRouteConfiguration(String routeName, String domain, String clusterName) {

        return EnvoyHelpers.createRouteConfiguration(routeName, domain, clusterName, "*");
    }

    public static RouteConfiguration createRouteConfiguration(String routeName, String domain, String clusterName, String domainNameFilter) {

        Set<TripleDomainNameFilterDomainNameClusterName> data = new HashSet<>(1);

        data.add(
                TripleDomainNameFilterDomainNameClusterName.tripleBuilder()
                        .domainNameFilter(domainNameFilter)
                        .domain(domain)
                        .clusterName(clusterName)
                        .tripleBuild()
        );

        return EnvoyHelpers.createRouteConfiguration(
                routeName,
                data
        );

        // return RouteConfiguration.newBuilder()
        //         .setName(routeName)
        //         .addVirtualHosts(
        //                 EnvoyHelpers.createVirtualHost(domainNameFilter, domain, clusterName)
        //         )
        //         .build();

    }

    public static RouteConfiguration createRouteConfiguration(String routeName, Set<TripleDomainNameFilterDomainNameClusterName> data) {

        RouteConfiguration.Builder builder = RouteConfiguration.newBuilder()
                .setName(routeName);

        for (TripleDomainNameFilterDomainNameClusterName triple : data) {

            builder.addVirtualHosts(
                    EnvoyHelpers.createVirtualHost(
                            triple.getDomainNameFilter(),
                            triple.getDomain(),
                            triple.getClusterName()
                    )
            );

        }

        return builder.build();

    }

    public static VirtualHost createVirtualHost(String domain, String clusterName) {

        return EnvoyHelpers.createVirtualHost("*", domain, clusterName);
    }

    public static VirtualHost createVirtualHost(String domainsNameFilter, String domain, String clusterName) {

        return VirtualHost.newBuilder()
                .setName("app_" + domain + "_C_" + clusterName)
                .addDomains(domainsNameFilter)
                .addRoutes(
                        Route.newBuilder()
                                .setMatch(
                                        RouteMatch.newBuilder()
                                                .setPrefix("/")
                                                .build()
                                )
                                .setRoute(
                                        RouteAction.newBuilder()
                                                .setCluster(clusterName)
                                                .build()
                                )
                                .build()
                )
                .build();

    }



    public static Address createAddress(String address, int port) {

        return Address.newBuilder()
                .setSocketAddress(
                        SocketAddress.newBuilder()
                                .setAddress(address)
                                .setPortValue(port)
                                .build()
                )
                .build();

    }

    public static Secret createTlsSecret(String name, String certificateChain, String privateKey) {

        return Secret.newBuilder()
                .setName(name)
                .setTlsCertificate(
                        TlsCertificate.newBuilder()
                                .setCertificateChain(
                                        DataSource.newBuilder()
                                                // .setInlineBytes()
                                                .setInlineString(certificateChain)
                                                .build()
                                )
                                .setPrivateKey(
                                        DataSource.newBuilder()
                                                // .setInlineBytes()
                                                .setInlineString(privateKey)
                                                .build()
                                )
                                .build()
                )
                // .setSessionTicketKeys(
                //         TlsSessionTicketKeys.newBuilder().build()
                // )
                // .setValidationContext(
                //         CertificateValidationContext.newBuilder().build()
                // )
                // .setGenericSecret(
                //         GenericSecret.newBuilder().build()
                // )
                .build();

    }


    public static Listener createDynamicListener(
            String name,
            String address,
            int port,
            Set<HttpConfig> httpConfigs
    ) {

        return EnvoyHelpers.createDynamicListener(
                name,
                address,
                port,
                httpConfigs,
                null
        );
    }

    public static Listener updateDynamicListener(
            @NotNull Listener listener,
            Set<HttpConfig> httpConfigs
    ) {

        return EnvoyHelpers.createDynamicListener(
                listener.getName(),
                listener.getAddress().getSocketAddress().getAddress(),
                listener.getAddress().getSocketAddress().getPortValue(),
                httpConfigs,
                listener
        );
    }

    public static Listener createDynamicListener(
            String name,
            String address,
            int port,
            Set<HttpConfig> httpConfigs,
            @Nullable Listener listener
    ) {

        log.info(
                "Create new dynamic listener with name <{}>, address <{}> and port <{}> and with provided listener ? <{}>",
                name,
                address,
                port,
                listener != null
        );

        ListenerFilter listenerFilterTlsInspector = ListenerFilter.newBuilder()
                .setName("envoy.filters.listener.tls_inspector")
                .build();

        ListenerFilter listenerFilterHttpInspector = ListenerFilter.newBuilder()
                .setName("envoy.filters.listener.http_inspector")
                .build();

        Listener.Builder listenerBuilder;
        if (listener != null) {
            listenerBuilder = Listener.newBuilder(listener);

        } else {
            listenerBuilder = Listener.newBuilder();

        }

        listenerBuilder
                .setName(name)

                .setAddress(
                        EnvoyHelpers.createAddress(address, port)
                )

                .addListenerFilters(
                        listenerFilterTlsInspector
                )
                .addListenerFilters(
                        listenerFilterHttpInspector
                );

        if (httpConfigs != null) {
            listenerBuilder.clearFilterChains();

            for (HttpConfig httpConfig : httpConfigs) {
                listenerBuilder.addFilterChains(
                        EnvoyHelpers.createFilterChainForDynamicListener(httpConfig)
                );
            }
        } else {
            // For LDS, a FilterChain must be specified
            listenerBuilder.addFilterChains(
                    FilterChain.newBuilder()
                            .setName("empty")
                            .build()
            );

        }

        return listenerBuilder.build();
    }

    public static FilterChain createFilterChainForDynamicListener(
            HttpConfig httpConfig
    ) {

        log.debug("Create FilterChain for data : {}", httpConfig);

        HttpConnectionManager httpConnectionManager = HttpConnectionManager.newBuilder()
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStatPrefix("ingress_http") // TODO - FROM VAR ?

                .addAccessLog(
                        AccessLog.newBuilder()
                                .setName("envoy.access_loggers.stdout")
                                .setTypedConfig(
                                        Any.newBuilder()
                                                .setTypeUrl("type.googleapis.com/envoy.extensions.access_loggers.stream.v3.StdoutAccessLog")
                                                .build()
                                )
                                .build()
                )

                .addHttpFilters(
                        HttpFilter.newBuilder()
                                .setName("envoy.filters.http.router")
                                .build()
                )

                // Routes with RDS
                .setRds(
                        Rds.newBuilder()
                                .setRouteConfigName(httpConfig.getRdsConfigName())
                                .setConfigSource(
                                        EnvoyHelpers.createConfigSourceWithDefaultAds()
                                )
                                .build()
                )
                .build();


        Filter filter = Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(
                        Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                                .setValue(httpConnectionManager.toByteString())
                                .build()
                )
                .build();


        // Main filter chain to return
        log.info("SNI domains for FilterChainMatch <{}>", String.join(",", httpConfig.getDomainNames()));

        // SNI
        FilterChainMatch.Builder filterChainMatchBuilder = FilterChainMatch.newBuilder();
        for (String domainName : httpConfig.getDomainNames()) {
            filterChainMatchBuilder.addServerNames(domainName); // SNI domains
        }

        FilterChain.Builder filterChainBuilder = FilterChain.newBuilder()
                .setFilterChainMatch(
                        filterChainMatchBuilder.build()
                );


        // TLS with SDS
        // ############
        CommonTlsContext.Builder commonTlsContextBuilder = CommonTlsContext.newBuilder();
        if (!httpConfig.getSdsConfigNames().isEmpty()) {

            for (String sdsSecretConfigName : httpConfig.getSdsConfigNames()) {
                commonTlsContextBuilder.addTlsCertificateSdsSecretConfigs(
                        SdsSecretConfig.newBuilder()
                                .setName(sdsSecretConfigName)
                                .setSdsConfig(
                                        EnvoyHelpers.createConfigSourceWithDefaultAds()
                                )
                                .build()
                );
            }

        } else {

            // For HTTPS work, need to serve a cert, so here we put the default cert
            commonTlsContextBuilder.addTlsCertificateSdsSecretConfigs(
                            SdsSecretConfig.newBuilder()
                                    .setName(ConfigurationLoader.DEFAULT_SECRET_NAME)
                                    .setSdsConfig(
                                            EnvoyHelpers.createConfigSourceWithDefaultAds()
                                    )
                                    .build()
                    );

        } // END IF sdsConfigNames is not empty

        DownstreamTlsContext downstreamTlsContext = DownstreamTlsContext.newBuilder()
                .setCommonTlsContext(
                        commonTlsContextBuilder
                                .build()
                )
                .build();

        filterChainBuilder.setTransportSocket(
                TransportSocket.newBuilder()
                        .setName("envoy.transport_sockets.tls") // Envoy specific name
                        .setTypedConfig(
                                Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext")
                                        .setValue(downstreamTlsContext.toByteString())
                                        .build()
                        )
                        .build()
        );
        // ########
        // /TLS SDS

        filterChainBuilder.addFilters(
                filter
        );

        return filterChainBuilder.build();
    }


    public static ConfigSource createConfigSourceWithDefaultAds() {

        return ConfigSource.newBuilder()
                .setResourceApiVersion(
                        ApiVersion.V3
                )
                .setAds(
                        AggregatedConfigSource.getDefaultInstance() // Empty here for 'ads: {}' config
                )
                .build();

    }




    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PairDomainClusterName {

        @NotBlank
        private String domain;

        @NotBlank
        private String clusterName;

    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripleDomainNameFilterDomainNameClusterName extends PairDomainClusterName {

        @NotBlank
        private String domainNameFilter = "*";




        @Builder(builderMethodName = "tripleBuilder", buildMethodName = "tripleBuild")
        public TripleDomainNameFilterDomainNameClusterName(@NotBlank String domain, @NotBlank String clusterName, String domainNameFilter) {
            super(domain, clusterName);
            this.domainNameFilter = domainNameFilter;
        }
    }

    @Data
    public static class ListenerData {

        @NotEmpty
        Set<String> domainNames = new HashSet<>(1);

        @NotEmpty
        Set<String> sdsConfigNames = new HashSet<>(0);


    }

    @Data
    public static class HttpConfig {

        // Use for 'serverNames' field of FilterChainMatch
        @NotEmpty
        Set<String> domainNames = new HashSet<>(1);

        @NotBlank
        String rdsConfigName;

        Set<String> sdsConfigNames = new HashSet<>(0);

    }

}