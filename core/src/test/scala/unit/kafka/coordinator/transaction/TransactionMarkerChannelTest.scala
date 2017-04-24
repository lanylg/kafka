/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.coordinator.transaction

import kafka.api.{LeaderAndIsr, PartitionStateInfo}
import kafka.controller.LeaderIsrAndControllerEpoch
import kafka.server.MetadataCache
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.{Errors, SecurityProtocol}
import org.apache.kafka.common.requests.{TransactionResult, WriteTxnMarkersRequest}
import org.apache.kafka.common.utils.Utils
import org.apache.kafka.common.{Node, TopicPartition}
import org.easymock.EasyMock
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

class TransactionMarkerChannelTest {

  private val metadataCache = EasyMock.createNiceMock(classOf[MetadataCache])
  private val listenerName = ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT)
  private val channel = new TransactionMarkerChannel(listenerName, metadataCache)


  @Test
  def shouldAddEmptyBrokerQueueWhenAddingNewBroker(): Unit = {
    channel.addOrUpdateBroker(new Node(1, "host", 10))
    channel.addOrUpdateBroker(new Node(2, "host", 10))
    assertEquals(0, channel.brokerStateMap(1).markersQueue.size())
    assertEquals(0, channel.brokerStateMap(2).markersQueue.size())
  }

  @Test
  def shouldUpdateDestinationBrokerNodeWhenUpdatingBroker(): Unit = {
    val partition1 = new TopicPartition("topic1", 0)
    val newDestination = new Node(1, "otherhost", 100)

    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))

    // getAliveEndpoint returns an updated node
    EasyMock.expect(metadataCache.getAliveEndpoint(1, listenerName)).andReturn(Some(newDestination))
    EasyMock.replay(metadataCache)

    channel.addOrUpdateBroker(new Node(1, "host", 10))
    channel.addRequestToSend(0, 0, 0, TransactionResult.COMMIT, 0, Set[TopicPartition](partition1))

    val destinationAndQueue = channel.brokerStateMap(1)
    assertEquals(newDestination, destinationAndQueue.destBrokerNode)
    assertEquals(1, destinationAndQueue.markersQueue.size())
  }

  @Test
  def shouldQueueRequestsByBrokerId(): Unit = {
    channel.addOrUpdateBroker(new Node(1, "host", 10))
    channel.addOrUpdateBroker(new Node(2, "otherhost", 10))
    channel.addRequestForBroker(1, CoordinatorEpochAndMarkers(0, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))
    channel.addRequestForBroker(1, CoordinatorEpochAndMarkers(0, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))
    channel.addRequestForBroker(2, CoordinatorEpochAndMarkers(0, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))

    assertEquals(2, channel.brokerStateMap(1).markersQueue.size())
    assertEquals(1, channel.brokerStateMap(2).markersQueue.size())
  }

  @Test
  def shouldNotAddPendingTxnIfOneAlreadyExistsForPid(): Unit = {
    channel.maybeAddPendingRequest(0, new TransactionMetadata(0, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0))
    assertFalse(channel.maybeAddPendingRequest(0, new TransactionMetadata(0, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0)))
  }

  @Test
  def shouldAddRequestsToCorrectBrokerQueues(): Unit = {
    val partition1 = new TopicPartition("topic1", 0)
    val partition2 = new TopicPartition("topic1", 1)

    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.expect(metadataCache.getPartitionInfo(partition2.topic(), partition2.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(2, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.expect(metadataCache.getAliveEndpoint(1, listenerName)).andReturn(Some(new Node(1, "host", 10)))
    EasyMock.expect(metadataCache.getAliveEndpoint(2, listenerName)).andReturn(Some(new Node(2, "otherhost", 10)))

    EasyMock.replay(metadataCache)
    channel.addRequestToSend(0, 0, 0, TransactionResult.COMMIT, 0, Set[TopicPartition](partition1, partition2))

    assertEquals(1, channel.brokerStateMap(1).markersQueue.size())
    assertEquals(1, channel.brokerStateMap(2).markersQueue.size())
  }

  @Test
  def shouldAddNewBrokerQueueIfDoesntAlreadyExistWhenAddingRequest(): Unit = {
    val partition1 = new TopicPartition("topic1", 0)

    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))
    EasyMock.expect(metadataCache.getAliveEndpoint(1, listenerName)).andReturn(Some(new Node(1, "host", 10)))

    EasyMock.replay(metadataCache)
    channel.addRequestToSend(0, 0, 0, TransactionResult.COMMIT, 0, Set[TopicPartition](partition1))

    assertEquals(1, channel.brokerStateMap(1).markersQueue.size())
    EasyMock.verify(metadataCache)
  }

  @Test
  def shouldGetPendingTxnMetadataByPid(): Unit = {
    val metadataPartition = 0
    val transaction = new TransactionMetadata(1, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0)
    channel.maybeAddPendingRequest(metadataPartition, transaction)
    channel.maybeAddPendingRequest(metadataPartition, new TransactionMetadata(2, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0))
    assertEquals(Some(transaction), channel.pendingTxnMetadata(metadataPartition, 1))
  }

  @Test
  def shouldRemovePendingRequestsForPartitionWhenPartitionEmigrated(): Unit = {
    channel.maybeAddPendingRequest(0, new TransactionMetadata(0, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0))
    channel.maybeAddPendingRequest(0, new TransactionMetadata(1, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0))
    val metadata = new TransactionMetadata(2, 0, 0, PrepareCommit, mutable.Set.empty, 0, 0)
    channel.maybeAddPendingRequest(1, metadata)

    channel.removeStateForPartition(0)

    assertEquals(None, channel.pendingTxnMetadata(0, 0))
    assertEquals(None, channel.pendingTxnMetadata(0, 1))
    assertEquals(Some(metadata), channel.pendingTxnMetadata(1, 2))
  }

  @Test
  def shouldRemoveBrokerRequestsForPartitionWhenPartitionEmigrated(): Unit = {
    channel.addOrUpdateBroker(new Node(1, "host", 10))
    channel.addRequestForBroker(1, CoordinatorEpochAndMarkers(0, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))
    channel.addRequestForBroker(1, CoordinatorEpochAndMarkers(1, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))
    channel.addRequestForBroker(1, CoordinatorEpochAndMarkers(1, 0, Utils.mkList(new WriteTxnMarkersRequest.TxnMarkerEntry(0, 0, TransactionResult.COMMIT, Utils.mkList()))))

    channel.removeStateForPartition(1)
    val markersQueue = channel.brokerStateMap(1).markersQueue
    assertEquals(1, markersQueue.size())
    assertEquals(0, markersQueue.peek().metadataPartition)

  }

  def errorCallback(error: Errors): Unit = {}
}