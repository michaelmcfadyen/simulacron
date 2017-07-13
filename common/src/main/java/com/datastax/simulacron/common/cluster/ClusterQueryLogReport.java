package com.datastax.simulacron.common.cluster;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Represent a class that contains the querylogs of a particular cluster. It's useful for encoding
 * the results with JSON.
 */
public class ClusterQueryLogReport extends QueryLogReport
    implements ClusterStructure<DataCenterQueryLogReport, NodeQueryLogReport> {
  @JsonManagedReference
  @JsonProperty("data_centers")
  private final Collection<DataCenterQueryLogReport> dataCenters = new TreeSet<>();

  @SuppressWarnings("unused")
  ClusterQueryLogReport() {
    // Default constructor for jackson deserialization.
    this(null);
  }

  public ClusterQueryLogReport(Long id) {
    super(id);
  }

  public NodeQueryLogReport addNode(AbstractNode node, List<QueryLog> logs) {
    Long dcId = node.getDataCenter().getId();
    Optional<DataCenterQueryLogReport> optionalDatacenterReport =
        dataCenters.stream().filter(dc -> dc.getId().equals(dcId)).findFirst();
    DataCenterQueryLogReport datacenterReport;
    if (optionalDatacenterReport.isPresent()) {
      datacenterReport = optionalDatacenterReport.get();
    } else {
      datacenterReport = new DataCenterQueryLogReport(dcId, this);
      this.addDataCenter(datacenterReport);
    }
    NodeQueryLogReport nodeReport = new NodeQueryLogReport(node.getId(), logs, datacenterReport);
    datacenterReport.addNode(nodeReport);
    return nodeReport;
  }

  void addDataCenter(DataCenterQueryLogReport dataCenter) {
    this.dataCenters.add(dataCenter);
  }

  public Collection<DataCenterQueryLogReport> getDataCenters() {
    return dataCenters;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ClusterConnectionReport that = (ClusterConnectionReport) o;

    return this.getId().equals(that.getId());
  }

  @Override
  public int hashCode() {
    return dataCenters != null ? dataCenters.hashCode() : 0;
  }

  @Override
  public ClusterQueryLogReport getRootReport() {
    return this;
  }

  @Override
  public List<QueryLog> getQueryLogs() {
    return getNodes().stream().flatMap(n -> n.getQueryLogs().stream()).collect(Collectors.toList());
  }
}
