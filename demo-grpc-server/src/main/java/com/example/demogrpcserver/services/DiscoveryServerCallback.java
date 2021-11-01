package com.example.demogrpcserver.services;

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryServerCallback implements DiscoveryServerCallbacks {

    private final ConfigurationLoader configurationLoader;



    @Override
    public void onStreamOpen(long streamId, String typeUrl) {
        log.debug("open streamId : [{}] - typeUrl : [{}]", streamId, typeUrl);
    }

    @Override
    public void onStreamClose(long streamId, String typeUrl) {
        log.debug("close streamId : [{}] - typeUrl : [{}]", streamId, typeUrl);
    }

    @Override
    public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
        log.debug("close w/ errors: streamId : [{}] - typeUrl : [{}] - error : [{}]", streamId, typeUrl, error.getMessage());
    }

    @Override
    public void onV2StreamRequest(long streamId, io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
        throw new IllegalStateException("Unexpected v2 request in v3");
    }

    @Override
    public void onStreamResponse(
            long streamId,
            io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
            io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
        throw new IllegalStateException("Unexpected v2 response in v3");
    }

    @Override
    public void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
        String id = UUID.randomUUID().toString();
        String idStr = String.format("[%s]", id);

        log.debug(
                "{} - [{}] - RESPONSE - V3 stream response streamId : [{}]{}response : [{}]",
                idStr,
                response.getNonce(),
                streamId,
                System.lineSeparator(),
                ToStringBuilder.reflectionToString(response)
        );

        log.debug(
                "{} - [{}] - RESPONSE - V3 stream response streamId : [{}] - With nonce <{}> / version info <{}> / and resources count <{}> for TypeURL <{}>",
                idStr,
                response.getNonce(),
                streamId,
                response.getNonce(),
                response.getVersionInfo(),
                response.getResourcesCount(),
                response.getTypeUrl()
        );

        log.debug(
                "{} - [{}] - RESPONSE - V3 stream response for request streamId : [{}] - With nonce <{}> / version info <{}> / and resources count <{}> for TypeURL <{}>",
                idStr,
                response.getNonce(),
                streamId,
                request.getResponseNonce(),
                request.getVersionInfo(),
                request.getResourceNamesCount(),
                request.getTypeUrl()
        );

    }

    @Override
    public void onV3StreamRequest(long streamId, DiscoveryRequest request) {

        String id = UUID.randomUUID().toString();
        String idStr = String.format("[%s]", id);

        log.debug(
                "{} - [{}] - REQUEST - V3 stream request streamId : [{}] - With nonce <{}> / version info <{}> / and resources count <{}> for TypeURL <{}>",
                idStr,
                request.getResponseNonce(),
                streamId,
                request.getResponseNonce(),
                request.getVersionInfo(),
                request.getResourceNamesCount(),
                request.getTypeUrl()
        );


        log.debug(
                "{} - [{}] - REQUEST - stream request streamId : [{}]{}node : [id : {}, cluster: {}, meta: {}]{}type : [{}]{}error : [{}]{}version : [{}]",
                idStr,
                request.getResponseNonce(),
                streamId,
                System.lineSeparator(),
                request.getNode().getId(),
                request.getNode().getCluster(),
                request.getNode().getMetadata(),
                System.lineSeparator(),
                request.getTypeUrl(),
                System.lineSeparator(),
                request.getErrorDetail(),
                System.lineSeparator(),
                request.getVersionInfo()
        );

        log.debug(
                "{} - [{}] - REQUEST - stream request StreamId : [{}]{}node : Resource : {}",
                idStr,
                request.getResponseNonce(),
                streamId,
                System.lineSeparator(),
                String.join("/", request.getResourceNamesList())
        );

        // Errors management
        if (request.getErrorDetail().getCode() != 0) {

            log.error(
                    "{} - [{}] - REQUEST - V3 stream request streamId : [{}] - Error details detected in Request : ({})<{}>",
                    idStr,
                    request.getResponseNonce(),
                    streamId,
                    request.getErrorDetail().getCode(),
                    request.getErrorDetail().getMessage()
            );

            log.error(
                    "{} - [{}] - REQUEST - V3 stream request streamId : [{}] - With nonce <{}> / version info <{}> / and resources count <{}> for TypeURL <{}>",
                    idStr,
                    request.getResponseNonce(),
                    streamId,
                    request.getResponseNonce(),
                    request.getVersionInfo(),
                    request.getResourceNamesCount(),
                    request.getTypeUrl()
            );

            // Try to load the last version without error
            this.configurationLoader.resetToTheLastGoodSnapshot(
                    request.getTypeUrl(),
                    request.getVersionInfo()
            );

        }

    }
}
