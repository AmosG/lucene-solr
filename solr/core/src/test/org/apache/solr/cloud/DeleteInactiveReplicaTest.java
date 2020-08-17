/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.util.FileUtils;
import org.apache.solr.util.TimeOut;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore // nocommit debug
public class DeleteInactiveReplicaTest extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void setupCluster() throws Exception {
    useFactory(null);
    configureCluster(4)
        .addConfig("conf", configset("cloud-minimal"))
        .withProperty(ZkStateReader.LEGACY_CLOUD, "false")
        .configure();
  }

  @Test
  public void deleteInactiveReplicaTest() throws Exception {

    String collectionName = "delDeadColl";
    int replicationFactor = 2;
    int numShards = 2;
    int maxShardsPerNode = ((((numShards + 1) * replicationFactor) / cluster.getJettySolrRunners().size())) + 1;

    CollectionAdminRequest.createCollection(collectionName, "conf", numShards, replicationFactor)
        .setMaxShardsPerNode(maxShardsPerNode)
        .process(cluster.getSolrClient());

    DocCollection collectionState = getCollectionState(collectionName);

    Slice shard = getRandomShard(collectionState);
    Replica replica = getRandomReplica(shard);
    JettySolrRunner jetty = cluster.getReplicaJetty(replica);

    cluster.stopJettySolrRunner(jetty);

    cluster.getSolrClient().getZkStateReader().waitForState(collectionName, 10, TimeUnit.SECONDS, (n,c)->{
      if (c == null) return false;
      Replica rep = c.getReplica(replica.getName());
      if (rep == null) return true;
      if (rep.getState() != Replica.State.ACTIVE) {
        return true;
      }
      return false;
    });

    if (log.isInfoEnabled()) {
      log.info("Removing replica {}/{} ", shard.getName(), replica.getName());
    }
    CollectionAdminRequest.deleteReplica(collectionName, shard.getName(), replica.getName())
        .process(cluster.getSolrClient());

    cluster.getSolrClient().getZkStateReader().waitForState(collectionName, 10, TimeUnit.SECONDS, (n,c)->{
      if (c == null) return false;
      if (c.getReplica(replica.getName()) == null) return true;
      return false;
    });

//    cluster.startJettySolrRunner(jetty);
//    log.info("restarted jetty");
//
//    // Check that we can't create a core with no coreNodeName
//    try (SolrClient queryClient = getHttpSolrClient(jetty.getBaseUrl().toString())) {
//      Exception e = expectThrows(Exception.class, () -> {
//        CoreAdminRequest.Create createRequest = new CoreAdminRequest.Create();
//        createRequest.setCoreName("testcore");
//        createRequest.setCollection(collectionName);
//        createRequest.setShardId("shard2");
//        queryClient.request(createRequest);
//      });
//      assertTrue("Unexpected error message: " + e.getMessage(), e.getMessage().contains("No coreNodeName for"));
//
//    }
  }
}
