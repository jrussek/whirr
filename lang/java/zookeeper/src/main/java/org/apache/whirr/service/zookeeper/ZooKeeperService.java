package org.apache.whirr.service.zookeeper;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.whirr.service.ClusterSpec;
import org.apache.whirr.service.ComputeServiceBuilder;
import org.apache.whirr.service.RunUrlBuilder;
import org.apache.whirr.service.Service;
import org.apache.whirr.service.ServiceSpec;
import org.apache.whirr.service.Cluster.Instance;
import org.apache.whirr.service.ClusterSpec.InstanceTemplate;
import org.apache.whirr.service.RunUrlScript;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;

public class ZooKeeperService extends Service {
    
  public static final String ZOOKEEPER_ROLE = "zk";
  private static final int CLIENT_PORT = 2181;
  
  public ZooKeeperService(ServiceSpec serviceSpec) {
    super(serviceSpec);
  }

  @Override
  public ZooKeeperCluster launchCluster(ClusterSpec clusterSpec) throws IOException {
      
    ComputeService computeService = ComputeServiceBuilder.build(serviceSpec);

    ArrayList<RunUrlScript> runUrls = new ArrayList<RunUrlScript>();
    runUrls.add(new RunUrlScript(
            "cloudera-tom.s3.amazonaws.com",
            "sun/java/install"));
    runUrls.add(new RunUrlScript(
            "cloudera-tom.s3.amazonaws.com",
            "apache/zookeeper/install"));
    byte[] bootScript = RunUrlBuilder.runUrls(runUrls);
    Template template = computeService.templateBuilder()
      .osFamily(OsFamily.UBUNTU)
      .options(runScript(bootScript)
	  .installPrivateKey(serviceSpec.readPrivateKey())
	  .authorizePublicKey(serviceSpec.readPublicKey())
	  .inboundPorts(22, CLIENT_PORT))
      .build();
    
    InstanceTemplate instanceTemplate = clusterSpec.getInstanceTemplate(ZOOKEEPER_ROLE);
    checkNotNull(instanceTemplate);
    int ensembleSize = instanceTemplate.getNumberOfInstances();
    Set<? extends NodeMetadata> nodeMap;
    try {
      nodeMap = computeService.runNodesWithTag(serviceSpec.getClusterName(), ensembleSize,
	  template);
    } catch (RunNodesException e) {
      // TODO: can we do better here - proceed if ensemble is big enough?
      throw new IOException(e);
    }
    List<NodeMetadata> nodes = Lists.newArrayList(nodeMap);
    
    // Pass list of all servers in ensemble to configure script.
    // Position is significant: i-th server has id i.
    String servers = Joiner.on(' ').join(getPrivateIps(nodes));

    runUrls = new ArrayList<RunUrlScript>();
    runUrls.add(new RunUrlScript(
            "cloudera-tom.s3.amazonaws.com",
            "apache/zookeeper/post-configure", servers));

    byte[] configureScript = RunUrlBuilder.runUrls(runUrls);
    try {
      computeService.runScriptOnNodesWithTag(serviceSpec.getClusterName(), configureScript);
    } catch (RunScriptOnNodesException e) {
      // TODO: retry
      throw new IOException(e);
    }
    
    String hosts = Joiner.on(',').join(getHosts(nodes));
    return new ZooKeeperCluster(getInstances(nodes), hosts);
  }

  private List<String> getPrivateIps(List<NodeMetadata> nodes) {
    return Lists.transform(Lists.newArrayList(nodes),
	new Function<NodeMetadata, String>() {
      @Override
      public String apply(NodeMetadata node) {
	return Iterables.get(node.getPrivateAddresses(), 0).getHostAddress();
      }
    });
  }
  
  private Set<Instance> getInstances(List<NodeMetadata> nodes) {
    return Sets.newHashSet(Collections2.transform(Sets.newHashSet(nodes),
	new Function<NodeMetadata, Instance>() {
      @Override
      public Instance apply(NodeMetadata node) {
	return new Instance(Collections.singleton(ZOOKEEPER_ROLE),
	    Iterables.get(node.getPublicAddresses(), 0),
	    Iterables.get(node.getPrivateAddresses(), 0));
      }
    }));
  }
  
  private List<String> getHosts(List<NodeMetadata> nodes) {
    return Lists.transform(Lists.newArrayList(nodes),
	new Function<NodeMetadata, String>() {
      @Override
      public String apply(NodeMetadata node) {
	String publicIp =  Iterables.get(node.getPublicAddresses(), 0)
	  .getHostName();
	return String.format("%s:%d", publicIp, CLIENT_PORT);
      }
    });
  }

}
