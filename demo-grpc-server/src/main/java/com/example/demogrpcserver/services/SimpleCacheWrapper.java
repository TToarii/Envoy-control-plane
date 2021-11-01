package com.example.demogrpcserver.services;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SimpleCach wrapper 클래스
 * 
 * @author mrchopa
 *
 * @param <T>
 */
public class SimpleCacheWrapper<T> extends SimpleCache<T>{
	private static final Logger LOG = LoggerFactory.getLogger(SimpleCacheWrapper.class);
	
	private final HashSet<T> groups = new HashSet<T>();
	
	public SimpleCacheWrapper(NodeGroup<T> nodeGroup) {
		super(nodeGroup);
	}
	
	@Override
	public Snapshot getSnapshot(T group) {
		LOG.debug("Get snapshot called by : [{}]", group);
		
		return super.getSnapshot(group);
	}
	
	@Override
	public void setSnapshot(T group, Snapshot snapshot) {
        LOG.debug("Set snapshot called by : [{}]", group);

        super.setSnapshot(group, snapshot);
		
		groups.add(group);
	}
	
	@Override
	public boolean clearSnapshot(T group) {
        LOG.debug("Clear snapshot called by : [{}]", group);

		if(super.clearSnapshot(group)) {
			groups.remove(group);
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public Collection<T> groups() {
		return ImmutableSet.copyOf(groups);
	}
	

	public String makeNewVersion() {

        String version = Long.toString(System.currentTimeMillis());

        LOG.debug("Make new version for Snapshot : {}", version);

		return version;
	}
}
