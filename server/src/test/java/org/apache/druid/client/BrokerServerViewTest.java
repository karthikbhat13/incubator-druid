/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.druid.client.selector.HighestPriorityTierSelectorStrategy;
import org.apache.druid.client.selector.RandomServerSelectorStrategy;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.curator.CuratorTestBase;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.query.QueryToolChestWarehouse;
import org.apache.druid.query.QueryWatcher;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.planning.DataSourceAnalysis;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.server.initialization.ZkPathsConfig;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.TimelineLookup;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.apache.druid.timeline.partition.PartitionHolder;
import org.apache.druid.timeline.partition.SingleElementPartitionChunk;
import org.easymock.EasyMock;
import org.joda.time.Interval;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

public class BrokerServerViewTest extends CuratorTestBase
{
  private final ObjectMapper jsonMapper;
  private final ZkPathsConfig zkPathsConfig;

  private CountDownLatch segmentViewInitLatch;
  private CountDownLatch segmentAddedLatch;
  private CountDownLatch segmentRemovedLatch;

  private BatchServerInventoryView baseView;
  private BrokerServerView brokerServerView;

  public BrokerServerViewTest()
  {
    jsonMapper = TestHelper.makeJsonMapper();
    zkPathsConfig = new ZkPathsConfig();
  }

  @Before
  public void setUp() throws Exception
  {
    setupServerAndCurator();
    curator.start();
    curator.blockUntilConnected();
  }

  @Test
  public void testSingleServerAddedRemovedSegment() throws Exception
  {
    segmentViewInitLatch = new CountDownLatch(1);
    segmentAddedLatch = new CountDownLatch(1);
    segmentRemovedLatch = new CountDownLatch(1);

    setupViews();

    final DruidServer druidServer = new DruidServer(
        "localhost:1234",
        "localhost:1234",
        null,
        10000000L,
        ServerType.HISTORICAL,
        "default_tier",
        0
    );

    setupZNodeForServer(druidServer, zkPathsConfig, jsonMapper);

    final DataSegment segment = dataSegmentWithIntervalAndVersion("2014-10-20T00:00:00Z/P1D", "v1");
    final int partition = segment.getShardSpec().getPartitionNum();
    final Interval intervals = Intervals.of("2014-10-20T00:00:00Z/P1D");
    announceSegmentForServer(druidServer, segment, zkPathsConfig, jsonMapper);
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentViewInitLatch));
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentAddedLatch));

    TimelineLookup<String, ServerSelector> timeline = brokerServerView.getTimeline(
        DataSourceAnalysis.forDataSource(new TableDataSource("test_broker_server_view"))
    ).get();
    List<TimelineObjectHolder<String, ServerSelector>> serverLookupRes = timeline.lookup(intervals);
    Assert.assertEquals(1, serverLookupRes.size());

    TimelineObjectHolder<String, ServerSelector> actualTimelineObjectHolder = serverLookupRes.get(0);
    Assert.assertEquals(intervals, actualTimelineObjectHolder.getInterval());
    Assert.assertEquals("v1", actualTimelineObjectHolder.getVersion());

    PartitionHolder<ServerSelector> actualPartitionHolder = actualTimelineObjectHolder.getObject();
    Assert.assertTrue(actualPartitionHolder.isComplete());
    Assert.assertEquals(1, Iterables.size(actualPartitionHolder));

    ServerSelector selector = (actualPartitionHolder.iterator().next()).getObject();
    Assert.assertFalse(selector.isEmpty());
    Assert.assertEquals(segment, selector.getSegment());
    Assert.assertEquals(druidServer, selector.pick(null).getServer());
    Assert.assertNotNull(timeline.findChunk(intervals, "v1", partition));

    unannounceSegmentForServer(druidServer, segment, zkPathsConfig);
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentRemovedLatch));

    Assert.assertEquals(
        0,
        timeline.lookup(intervals).size()
    );
    Assert.assertNull(timeline.findChunk(intervals, "v1", partition));
  }

  @Test
  public void testMultipleServerAddedRemovedSegment() throws Exception
  {
    segmentViewInitLatch = new CountDownLatch(1);
    segmentAddedLatch = new CountDownLatch(5);

    // temporarily set latch count to 1
    segmentRemovedLatch = new CountDownLatch(1);

    setupViews();

    final List<DruidServer> druidServers = Lists.transform(
        ImmutableList.of("locahost:0", "localhost:1", "localhost:2", "localhost:3", "localhost:4"),
        input -> new DruidServer(
            input,
            input,
            null,
            10000000L,
            ServerType.HISTORICAL,
            "default_tier",
            0
        )
    );

    for (DruidServer druidServer : druidServers) {
      setupZNodeForServer(druidServer, zkPathsConfig, jsonMapper);
    }

    final List<DataSegment> segments = Lists.transform(
        ImmutableList.of(
            Pair.of("2011-04-01/2011-04-03", "v1"),
            Pair.of("2011-04-03/2011-04-06", "v1"),
            Pair.of("2011-04-01/2011-04-09", "v2"),
            Pair.of("2011-04-06/2011-04-09", "v3"),
            Pair.of("2011-04-01/2011-04-02", "v3")
        ), input -> dataSegmentWithIntervalAndVersion(input.lhs, input.rhs)
    );

    for (int i = 0; i < 5; ++i) {
      announceSegmentForServer(druidServers.get(i), segments.get(i), zkPathsConfig, jsonMapper);
    }
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentViewInitLatch));
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentAddedLatch));

    TimelineLookup timeline = brokerServerView.getTimeline(
        DataSourceAnalysis.forDataSource(new TableDataSource("test_broker_server_view"))
    ).get();
    assertValues(
        Arrays.asList(
            createExpected("2011-04-01/2011-04-02", "v3", druidServers.get(4), segments.get(4)),
            createExpected("2011-04-02/2011-04-06", "v2", druidServers.get(2), segments.get(2)),
            createExpected("2011-04-06/2011-04-09", "v3", druidServers.get(3), segments.get(3))
        ),
        (List<TimelineObjectHolder>) timeline.lookup(
            Intervals.of(
                "2011-04-01/2011-04-09"
            )
        )
    );

    // unannounce the segment created by dataSegmentWithIntervalAndVersion("2011-04-01/2011-04-09", "v2")
    unannounceSegmentForServer(druidServers.get(2), segments.get(2), zkPathsConfig);
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentRemovedLatch));

    // renew segmentRemovedLatch since we still have 4 segments to unannounce
    segmentRemovedLatch = new CountDownLatch(4);

    timeline = brokerServerView.getTimeline(
        DataSourceAnalysis.forDataSource(new TableDataSource("test_broker_server_view"))
    ).get();
    assertValues(
        Arrays.asList(
            createExpected("2011-04-01/2011-04-02", "v3", druidServers.get(4), segments.get(4)),
            createExpected("2011-04-02/2011-04-03", "v1", druidServers.get(0), segments.get(0)),
            createExpected("2011-04-03/2011-04-06", "v1", druidServers.get(1), segments.get(1)),
            createExpected("2011-04-06/2011-04-09", "v3", druidServers.get(3), segments.get(3))
        ),
        (List<TimelineObjectHolder>) timeline.lookup(
            Intervals.of(
                "2011-04-01/2011-04-09"
            )
        )
    );

    // unannounce all the segments
    for (int i = 0; i < 5; ++i) {
      // skip the one that was previously unannounced
      if (i != 2) {
        unannounceSegmentForServer(druidServers.get(i), segments.get(i), zkPathsConfig);
      }
    }
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentRemovedLatch));

    Assert.assertEquals(
        0,
        ((List<TimelineObjectHolder>) timeline.lookup(Intervals.of("2011-04-01/2011-04-09"))).size()
    );
  }

  @Test
  public void testMultipleServerAndBroker() throws Exception
  {
    segmentViewInitLatch = new CountDownLatch(1);
    segmentAddedLatch = new CountDownLatch(6);

    // temporarily set latch count to 1
    segmentRemovedLatch = new CountDownLatch(1);

    setupViews();

    final DruidServer druidBroker = new DruidServer(
        "localhost:5",
        "localhost:5",
        null,
        10000000L,
        ServerType.BROKER,
        "default_tier",
        0
    );

    final List<DruidServer> druidServers = Lists.transform(
        ImmutableList.of("locahost:0", "localhost:1", "localhost:2", "localhost:3", "localhost:4"),
        input -> new DruidServer(
            input,
            input,
            null,
            10000000L,
            ServerType.HISTORICAL,
            "default_tier",
            0
        )
    );

    setupZNodeForServer(druidBroker, zkPathsConfig, jsonMapper);
    for (DruidServer druidServer : druidServers) {
      setupZNodeForServer(druidServer, zkPathsConfig, jsonMapper);
    }

    final List<DataSegment> segments = Lists.transform(
        ImmutableList.of(
            Pair.of("2011-04-01/2011-04-03", "v1"),
            Pair.of("2011-04-03/2011-04-06", "v1"),
            Pair.of("2011-04-01/2011-04-09", "v2"),
            Pair.of("2011-04-06/2011-04-09", "v3"),
            Pair.of("2011-04-01/2011-04-02", "v3")
        ),
        input -> dataSegmentWithIntervalAndVersion(input.lhs, input.rhs)
    );

    DataSegment brokerSegment = dataSegmentWithIntervalAndVersion("2011-04-01/2011-04-11", "v4");
    announceSegmentForServer(druidBroker, brokerSegment, zkPathsConfig, jsonMapper);
    for (int i = 0; i < 5; ++i) {
      announceSegmentForServer(druidServers.get(i), segments.get(i), zkPathsConfig, jsonMapper);
    }
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentViewInitLatch));
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentAddedLatch));

    TimelineLookup timeline = brokerServerView.getTimeline(
        DataSourceAnalysis.forDataSource(new TableDataSource("test_broker_server_view"))
    ).get();

    assertValues(
        Arrays.asList(
            createExpected("2011-04-01/2011-04-02", "v3", druidServers.get(4), segments.get(4)),
            createExpected("2011-04-02/2011-04-06", "v2", druidServers.get(2), segments.get(2)),
            createExpected("2011-04-06/2011-04-09", "v3", druidServers.get(3), segments.get(3))
        ),
        (List<TimelineObjectHolder>) timeline.lookup(
            Intervals.of(
                "2011-04-01/2011-04-09"
            )
        )
    );

    // unannounce the broker segment should do nothing to announcements
    unannounceSegmentForServer(druidBroker, brokerSegment, zkPathsConfig);
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentRemovedLatch));

    // renew segmentRemovedLatch since we still have 5 segments to unannounce
    segmentRemovedLatch = new CountDownLatch(5);

    timeline = brokerServerView.getTimeline(
        DataSourceAnalysis.forDataSource(new TableDataSource("test_broker_server_view"))
    ).get();

    // expect same set of segments as before
    assertValues(
        Arrays.asList(
            createExpected("2011-04-01/2011-04-02", "v3", druidServers.get(4), segments.get(4)),
            createExpected("2011-04-02/2011-04-06", "v2", druidServers.get(2), segments.get(2)),
            createExpected("2011-04-06/2011-04-09", "v3", druidServers.get(3), segments.get(3))
        ),
        (List<TimelineObjectHolder>) timeline.lookup(
            Intervals.of(
                "2011-04-01/2011-04-09"
            )
        )
    );

    // unannounce all the segments
    for (int i = 0; i < 5; ++i) {
      unannounceSegmentForServer(druidServers.get(i), segments.get(i), zkPathsConfig);
    }
    Assert.assertTrue(timing.forWaiting().awaitLatch(segmentRemovedLatch));
  }


  private Pair<Interval, Pair<String, Pair<DruidServer, DataSegment>>> createExpected(
      String intervalStr,
      String version,
      DruidServer druidServer,
      DataSegment segment
  )
  {
    return Pair.of(Intervals.of(intervalStr), Pair.of(version, Pair.of(druidServer, segment)));
  }

  private void assertValues(
      List<Pair<Interval, Pair<String, Pair<DruidServer, DataSegment>>>> expected, List<TimelineObjectHolder> actual
  )
  {
    Assert.assertEquals(expected.size(), actual.size());

    for (int i = 0; i < expected.size(); ++i) {
      Pair<Interval, Pair<String, Pair<DruidServer, DataSegment>>> expectedPair = expected.get(i);
      TimelineObjectHolder<String, ServerSelector> actualTimelineObjectHolder = actual.get(i);

      Assert.assertEquals(expectedPair.lhs, actualTimelineObjectHolder.getInterval());
      Assert.assertEquals(expectedPair.rhs.lhs, actualTimelineObjectHolder.getVersion());

      PartitionHolder<ServerSelector> actualPartitionHolder = actualTimelineObjectHolder.getObject();
      Assert.assertTrue(actualPartitionHolder.isComplete());
      Assert.assertEquals(1, Iterables.size(actualPartitionHolder));

      ServerSelector selector = ((SingleElementPartitionChunk<ServerSelector>) actualPartitionHolder.iterator()
                                                                                                    .next()).getObject();
      Assert.assertFalse(selector.isEmpty());
      Assert.assertEquals(expectedPair.rhs.rhs.lhs, selector.pick(null).getServer());
      Assert.assertEquals(expectedPair.rhs.rhs.rhs, selector.getSegment());
    }
  }

  private void setupViews() throws Exception
  {
    baseView = new BatchServerInventoryView(
        zkPathsConfig,
        curator,
        jsonMapper,
        Predicates.alwaysTrue()
    )
    {
      @Override
      public void registerSegmentCallback(Executor exec, final SegmentCallback callback)
      {
        super.registerSegmentCallback(
            exec,
            new SegmentCallback()
            {
              @Override
              public CallbackAction segmentAdded(DruidServerMetadata server, DataSegment segment)
              {
                CallbackAction res = callback.segmentAdded(server, segment);
                segmentAddedLatch.countDown();
                return res;
              }

              @Override
              public CallbackAction segmentRemoved(DruidServerMetadata server, DataSegment segment)
              {
                CallbackAction res = callback.segmentRemoved(server, segment);
                segmentRemovedLatch.countDown();
                return res;
              }

              @Override
              public CallbackAction segmentViewInitialized()
              {
                CallbackAction res = callback.segmentViewInitialized();
                segmentViewInitLatch.countDown();
                return res;
              }
            }
        );
      }
    };

    brokerServerView = new BrokerServerView(
        EasyMock.createMock(QueryToolChestWarehouse.class),
        EasyMock.createMock(QueryWatcher.class),
        getSmileMapper(),
        EasyMock.createMock(HttpClient.class),
        baseView,
        new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy()),
        new NoopServiceEmitter(),
        new BrokerSegmentWatcherConfig()
    );

    baseView.start();
  }

  private DataSegment dataSegmentWithIntervalAndVersion(String intervalStr, String version)
  {
    return DataSegment.builder()
                      .dataSource("test_broker_server_view")
                      .interval(Intervals.of(intervalStr))
                      .loadSpec(
                          ImmutableMap.of(
                              "type",
                              "local",
                              "path",
                              "somewhere"
                          )
                      )
                      .version(version)
                      .dimensions(ImmutableList.of())
                      .metrics(ImmutableList.of())
                      .shardSpec(NoneShardSpec.instance())
                      .binaryVersion(9)
                      .size(0)
                      .build();
  }

  public ObjectMapper getSmileMapper()
  {
    final SmileFactory smileFactory = new SmileFactory();
    smileFactory.configure(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT, false);
    smileFactory.delegateToTextual(true);
    final ObjectMapper retVal = new DefaultObjectMapper(smileFactory);
    retVal.getFactory().setCodec(retVal);
    return retVal;
  }

  @After
  public void tearDown() throws Exception
  {
    baseView.stop();
    tearDownServerAndCurator();
  }
}
