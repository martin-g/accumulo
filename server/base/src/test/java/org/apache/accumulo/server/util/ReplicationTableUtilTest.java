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
package org.apache.accumulo.server.util;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.IteratorSetting.Column;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.clientImpl.Credentials;
import org.apache.accumulo.core.clientImpl.Writer;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.ReplicationSection;
import org.apache.accumulo.core.protobuf.ProtobufUtil;
import org.apache.accumulo.server.replication.StatusCombiner;
import org.apache.accumulo.server.replication.StatusUtil;
import org.apache.accumulo.server.replication.proto.Replication.Status;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.easymock.EasyMock;
import org.junit.Test;

@Deprecated
public class ReplicationTableUtilTest {

  @Test
  public void properPathInRow() throws Exception {
    Writer writer = EasyMock.createNiceMock(Writer.class);
    writer.update(EasyMock.anyObject(Mutation.class));
    final List<Mutation> mutations = new ArrayList<>();

    // Mock a Writer to just add the mutation to a list
    EasyMock.expectLastCall().andAnswer(() -> {
      mutations.add(((Mutation) EasyMock.getCurrentArguments()[0]));
      return null;
    });

    EasyMock.replay(writer);

    Credentials creds = new Credentials("root", new PasswordToken(""));
    ClientContext context = EasyMock.createMock(ClientContext.class);
    EasyMock.expect(context.getCredentials()).andReturn(creds).anyTimes();
    EasyMock.replay(context);

    // Magic hook to create a Writer
    ReplicationTableUtil.addWriter(creds, writer);

    // Example file seen coming out of LogEntry
    UUID uuid = UUID.randomUUID();
    String myFile = "file:////home/user/accumulo/wal/server+port/" + uuid;

    long createdTime = System.currentTimeMillis();
    ReplicationTableUtil.updateFiles(context, new KeyExtent(TableId.of("1"), null, null), myFile,
        StatusUtil.fileCreated(createdTime));

    verify(writer);

    assertEquals(1, mutations.size());
    Mutation m = mutations.get(0);

    assertEquals(
        ReplicationSection.getRowPrefix() + "file:/home/user/accumulo/wal/server+port/" + uuid,
        new Text(m.getRow()).toString());

    List<ColumnUpdate> updates = m.getUpdates();
    assertEquals(1, updates.size());
    ColumnUpdate update = updates.get(0);

    assertEquals(ReplicationSection.COLF, new Text(update.getColumnFamily()));
    assertEquals("1", new Text(update.getColumnQualifier()).toString());
    assertEquals(StatusUtil.fileCreatedValue(createdTime), new Value(update.getValue()));
  }

  @Test
  public void replEntryMutation() {
    // We stopped using a WAL -- we need a reference that this WAL needs to be replicated completely
    Status stat = Status.newBuilder().setBegin(0).setEnd(0).setInfiniteEnd(true)
        .setCreatedTime(System.currentTimeMillis()).build();
    String file = "file:///accumulo/wal/127.0.0.1+9997" + UUID.randomUUID();
    Path filePath = new Path(file);
    Text row = new Text(filePath.toString());
    KeyExtent extent = new KeyExtent(TableId.of("1"), new Text("b"), new Text("a"));

    Mutation m =
        ReplicationTableUtil.createUpdateMutation(filePath, ProtobufUtil.toValue(stat), extent);

    assertEquals(new Text(ReplicationSection.getRowPrefix() + row), new Text(m.getRow()));
    assertEquals(1, m.getUpdates().size());
    ColumnUpdate col = m.getUpdates().get(0);

    assertEquals(ReplicationSection.COLF, new Text(col.getColumnFamily()));
    assertEquals(extent.tableId().canonical(), new Text(col.getColumnQualifier()).toString());
    assertEquals(0, col.getColumnVisibility().length);
    assertArrayEquals(stat.toByteArray(), col.getValue());
  }

  @Test
  public void setsCombinerOnMetadataCorrectly() throws Exception {
    AccumuloClient client = createMock(AccumuloClient.class);
    TableOperations tops = createMock(TableOperations.class);

    String myMetadataTable = "mymetadata";
    Map<String,EnumSet<IteratorScope>> iterators = new HashMap<>();
    iterators.put("vers", EnumSet.of(IteratorScope.majc, IteratorScope.minc, IteratorScope.scan));
    IteratorSetting combiner = new IteratorSetting(9, "replcombiner", StatusCombiner.class);
    Combiner.setColumns(combiner, Collections.singletonList(new Column(ReplicationSection.COLF)));

    expect(client.tableOperations()).andReturn(tops);
    expect(tops.listIterators(myMetadataTable)).andReturn(iterators);
    tops.attachIterator(myMetadataTable, combiner);
    expectLastCall().once();

    expect(tops.getConfiguration(myMetadataTable)).andReturn(Collections.emptyMap());
    tops.setProperty(myMetadataTable, Property.TABLE_FORMATTER_CLASS.getKey(),
        ReplicationTableUtil.STATUS_FORMATTER_CLASS_NAME);
    expectLastCall().once();

    replay(client, tops);

    ReplicationTableUtil.configureMetadataTable(client, myMetadataTable);

    verify(client, tops);
  }
}
