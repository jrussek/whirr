package org.apache.whirr.service;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.jclouds.compute.ComputeService;

public abstract class Service {

  protected ServiceSpec serviceSpec;
  
  private final Hashtable<String, List<RunUrlScript>> runUrlScripts = new Hashtable();

  public Service(ServiceSpec serviceSpec) {
    this.serviceSpec = serviceSpec;
  }

  /**
   * add a list of runurl scripts to be run by the userdata on the nodes at a point
   * identified by label
   * @param label   the hook-label this script should be run by
   * @param scripts List of RunUrlScript Objects to run
   */
  public void addScript(String label, List<RunUrlScript> scripts) {
      if(runUrlScripts.get(label) != null) {
          runUrlScripts.get(label).addAll(scripts);
      }
      runUrlScripts.put(label, scripts);
  }

  /**
   * add a list of runurl scripts to be run by the userdata on the nodes after
   * all standard runurl scripts have been run
   * @param scripts List of RunUrlScript Objects to run
   */
  public void addScript(List<RunUrlScript> scripts) {
     addScript("DEFAULT",scripts);
  }

  /**
   * add a runurl script to be run by the userdata on the nodes at a point
   * identified by label
   * @param label
   * @param script
   */
  public void addScript(String label, RunUrlScript script) {
      ArrayList<RunUrlScript> scripts = new ArrayList<RunUrlScript>();
      scripts.add(script);
      addScript(label,scripts);
  }

  /**
   * add a runurl script to be run by the userdata on the nodes after all standard
   * runurl scripts have been run
   * @param script
   */
  public void addScript(RunUrlScript script) {
      addScript("DEFAULT",script);
  }

  /**
   * retrieve a list of RunUrlScript Objects identified by label
   * @param label   the label of the List
   * @return    returns a List<RunUrlscript> containing the Objects
   */
  public List<RunUrlScript> getByLabel(String label) {
     return new ArrayList<RunUrlScript>(runUrlScripts.get(label));
  }

  public abstract Cluster launchCluster(ClusterSpec clusterSpec)
    throws IOException;
  
  public void destroyCluster() throws IOException {
    ComputeService computeService = ComputeServiceBuilder.build(serviceSpec);
    computeService.destroyNodesWithTag(serviceSpec.getClusterName());
  }

}
