package org.apache.whirr.service.hadoop;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.compute.options.TemplateOptions.Builder.runScript;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.Map.Entry;

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
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;

public class HadoopService extends Service {
  
  public static final Set<String> MASTER_ROLE = Sets.newHashSet("nn", "jt");
  public static final Set<String> WORKER_ROLE = Sets.newHashSet("dn", "tt");




  public HadoopService(ServiceSpec serviceSpec) {
    super(serviceSpec);
  }

  @Override
  public HadoopCluster launchCluster(ClusterSpec clusterSpec) throws IOException {
    ComputeService computeService = ComputeServiceBuilder.build(serviceSpec);

    String privateKey = serviceSpec.readPrivateKey();
    String publicKey = serviceSpec.readPublicKey();
    
    // deal with user packages and autoshutdown with extra runurls
    ArrayList<RunUrlScript> runUrls = new ArrayList<RunUrlScript>();

    runUrls.addAll(getByLabel("BEFORE_JAVA"));

    runUrls.add(new RunUrlScript(
            "cloudera-tom.s3.amazonaws.com",
            "sun/java/install"));

    runUrls.addAll(getByLabel("AFTER_JAVA"));
    runUrls.addAll(getByLabel("BEFORE_HADOOP"));

    runUrls.add(new RunUrlScript(
            "cloudera-tom.s3.amazonaws.com",
            "apache/hadoop/install",
            "nn,jt",
            "-c", serviceSpec.getProvider()));

    runUrls.addAll(getByLabel("AFTER_HADOOP"));
    runUrls.addAll(getByLabel("DEFAULT"));

    byte[] nnjtBootScript = RunUrlBuilder.runUrls(runUrls);
    
    Template template = computeService.templateBuilder()
    .osFamily(OsFamily.UBUNTU)
    .options(runScript(nnjtBootScript)
        .installPrivateKey(privateKey)
	      .authorizePublicKey(publicKey)
	      .inboundPorts(22, 80, 8020, 8021, 50030)) // TODO: restrict further
    .build();
    
    InstanceTemplate instanceTemplate = clusterSpec.getInstanceTemplate(MASTER_ROLE);
    checkNotNull(instanceTemplate);
    checkArgument(instanceTemplate.getNumberOfInstances() == 1);
    Set<? extends NodeMetadata> nodes;
    try {
      nodes = computeService.runNodesWithTag(
      serviceSpec.getClusterName(), 1, template);
    } catch (RunNodesException e) {
      // TODO: can we do better here (retry?)
      throw new IOException(e);
    }
    NodeMetadata node = Iterables.getOnlyElement(nodes);
    InetAddress namenodePublicAddress = Iterables.getOnlyElement(node.getPublicAddresses());
    InetAddress jobtrackerPublicAddress = Iterables.getOnlyElement(node.getPublicAddresses());
    
    runUrls = new ArrayList<RunUrlScript>();

    runUrls.addAll(getByLabel("BEFORE_JAVA"));

    runUrls.add(new RunUrlScript(
                "cloudera-tom.s3.amazonaws.com",
                "sun/java/install"));

    runUrls.addAll(getByLabel("AFTER_JAVA"));
    runUrls.addAll(getByLabel("BEFORE_HADOOP"));

    runUrls.add(new RunUrlScript(
                "cloudera-tom.s3.amazonaws.com",
                "apache/hadoop/install",
                "dn,tt",
                "-n", namenodePublicAddress.getHostName(),
                "-j", jobtrackerPublicAddress.getHostName()));

    runUrls.addAll(getByLabel("AFTER_HADOOP"));
    runUrls.addAll(getByLabel("DEFAULT"));

    byte[] slaveBootScript = RunUrlBuilder.runUrls(runUrls);

    template = computeService.templateBuilder()
    .osFamily(OsFamily.UBUNTU)
    .options(runScript(slaveBootScript)
        .installPrivateKey(privateKey)
	      .authorizePublicKey(publicKey))
    .build();

    instanceTemplate = clusterSpec.getInstanceTemplate(WORKER_ROLE);
    checkNotNull(instanceTemplate);

    Set<? extends NodeMetadata> workerNodes;
    try {
      workerNodes = computeService.runNodesWithTag(serviceSpec.getClusterName(),
	instanceTemplate.getNumberOfInstances(), template);
    } catch (RunNodesException e) {
      // TODO: don't bail out if only a few have failed to start
      throw new IOException(e);
    }
    
    // TODO: wait for TTs to come up (done in test for the moment)
    
    Set<Instance> instances = Sets.union(getInstances(MASTER_ROLE, Collections.singleton(node)),
	getInstances(WORKER_ROLE, workerNodes));
    
    Properties config = createClientSideProperties(namenodePublicAddress, jobtrackerPublicAddress);
    return new HadoopCluster(instances, config);
  }
  
  private Set<Instance> getInstances(final Set<String> roles, Set<? extends NodeMetadata> nodes) {
    return Sets.newHashSet(Collections2.transform(Sets.newHashSet(nodes),
	new Function<NodeMetadata, Instance>() {
      @Override
      public Instance apply(NodeMetadata node) {
	return new Instance(roles,
	    Iterables.get(node.getPublicAddresses(), 0),
	    Iterables.get(node.getPrivateAddresses(), 0));
      }
    }));
  }
  
  private Properties createClientSideProperties(InetAddress namenode, InetAddress jobtracker) throws IOException {
      Properties config = new Properties();
      config.setProperty("hadoop.job.ugi", "root,root");
      config.setProperty("fs.default.name", String.format("hdfs://%s:8020/", namenode.getHostName()));
      config.setProperty("mapred.job.tracker", String.format("%s:8021", jobtracker.getHostName()));
      config.setProperty("hadoop.socks.server", "localhost:6666");
      config.setProperty("hadoop.rpc.socket.factory.class.default", "org.apache.hadoop.net.SocksSocketFactory");
      return config;
  }

  private void createClientSideHadoopSiteFile(InetAddress namenode, InetAddress jobtracker) throws IOException {
    File file = new File("/tmp/hadoop-site.xml");
    Files.write(generateHadoopConfigurationFile(createClientSideProperties(namenode, jobtracker)), file, Charsets.UTF_8);
  }
  
  private CharSequence generateHadoopConfigurationFile(Properties config) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\"?>\n");
    sb.append("<?xml-stylesheet type=\"text/xsl\" href=\"configuration.xsl\"?>\n");
    sb.append("<configuration>\n");
    for (Entry<Object, Object> entry : config.entrySet()) {
      sb.append("<property>\n");
      sb.append("<name>").append(entry.getKey()).append("</name>\n");
      sb.append("<value>").append(entry.getValue()).append("</value>\n");
      sb.append("</property>\n");
    }
    sb.append("</configuration>\n");
    return sb;
  }
  
}
