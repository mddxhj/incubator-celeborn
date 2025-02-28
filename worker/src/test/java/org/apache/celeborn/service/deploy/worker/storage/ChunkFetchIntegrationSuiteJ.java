/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.service.deploy.worker.storage;

import static org.apache.celeborn.common.util.JavaUtils.getLocalHost;
import static org.junit.Assert.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.celeborn.common.CelebornConf;
import org.apache.celeborn.common.network.TransportContext;
import org.apache.celeborn.common.network.buffer.FileSegmentManagedBuffer;
import org.apache.celeborn.common.network.buffer.ManagedBuffer;
import org.apache.celeborn.common.network.buffer.NioManagedBuffer;
import org.apache.celeborn.common.network.client.ChunkReceivedCallback;
import org.apache.celeborn.common.network.client.TransportClient;
import org.apache.celeborn.common.network.client.TransportClientFactory;
import org.apache.celeborn.common.network.protocol.ChunkFetchRequest;
import org.apache.celeborn.common.network.protocol.ChunkFetchSuccess;
import org.apache.celeborn.common.network.protocol.RequestMessage;
import org.apache.celeborn.common.network.protocol.StreamChunkSlice;
import org.apache.celeborn.common.network.server.BaseMessageHandler;
import org.apache.celeborn.common.network.server.TransportServer;
import org.apache.celeborn.common.network.util.TransportConf;

public class ChunkFetchIntegrationSuiteJ {
  static final long STREAM_ID = 1;
  static final int BUFFER_CHUNK_INDEX = 0;
  static final int FILE_CHUNK_INDEX = 1;

  static TransportServer server;
  static TransportClientFactory clientFactory;
  static ChunkStreamManager chunkStreamManager;
  static File testFile;

  static ManagedBuffer bufferChunk;
  static ManagedBuffer fileChunk;

  @BeforeClass
  public static void setUp() throws Exception {
    int bufSize = 100000;
    final ByteBuffer buf = ByteBuffer.allocate(bufSize);
    for (int i = 0; i < bufSize; i++) {
      buf.put((byte) i);
    }
    buf.flip();
    bufferChunk = new NioManagedBuffer(buf);

    testFile = File.createTempFile("shuffle-test-file", "txt");
    testFile.deleteOnExit();
    RandomAccessFile fp = new RandomAccessFile(testFile, "rw");
    boolean shouldSuppressIOException = true;
    try {
      byte[] fileContent = new byte[1024];
      new Random().nextBytes(fileContent);
      fp.write(fileContent);
      shouldSuppressIOException = false;
    } finally {
      Closeables.close(fp, shouldSuppressIOException);
    }

    final TransportConf conf = new TransportConf("shuffle", new CelebornConf());
    fileChunk = new FileSegmentManagedBuffer(conf, testFile, 10, testFile.length() - 25);

    chunkStreamManager =
        new ChunkStreamManager() {
          @Override
          public ManagedBuffer getChunk(long streamId, int chunkIndex, int offset, int len) {
            assertEquals(STREAM_ID, streamId);
            if (chunkIndex == BUFFER_CHUNK_INDEX) {
              return new NioManagedBuffer(buf);
            } else if (chunkIndex == FILE_CHUNK_INDEX) {
              return new FileSegmentManagedBuffer(conf, testFile, 10, testFile.length() - 25);
            } else {
              throw new IllegalArgumentException("Invalid chunk index: " + chunkIndex);
            }
          }
        };
    BaseMessageHandler handler =
        new BaseMessageHandler() {
          @Override
          public void receive(TransportClient client, RequestMessage msg) {
            StreamChunkSlice slice = ((ChunkFetchRequest) msg).streamChunkSlice;
            ManagedBuffer buf =
                chunkStreamManager.getChunk(
                    slice.streamId, slice.chunkIndex, slice.offset, slice.len);
            client.getChannel().writeAndFlush(new ChunkFetchSuccess(slice, buf));
          }

          @Override
          public boolean checkRegistered() {
            return true;
          }
        };
    TransportContext context = new TransportContext(conf, handler);
    server = context.createServer();
    clientFactory = context.createClientFactory();
  }

  @AfterClass
  public static void tearDown() {
    bufferChunk.release();
    server.close();
    clientFactory.close();
    testFile.delete();
  }

  static class FetchResult {
    public Set<Integer> successChunks;
    public Set<Integer> failedChunks;
    public List<ManagedBuffer> buffers;

    public void releaseBuffers() {
      for (ManagedBuffer buffer : buffers) {
        buffer.release();
      }
    }
  }

  private FetchResult fetchChunks(List<Integer> chunkIndices) throws Exception {
    TransportClient client = clientFactory.createClient(getLocalHost(), server.getPort());
    final Semaphore sem = new Semaphore(0);

    final FetchResult res = new FetchResult();
    res.successChunks = Collections.synchronizedSet(new HashSet<Integer>());
    res.failedChunks = Collections.synchronizedSet(new HashSet<Integer>());
    res.buffers = Collections.synchronizedList(new LinkedList<ManagedBuffer>());

    ChunkReceivedCallback callback =
        new ChunkReceivedCallback() {
          @Override
          public void onSuccess(int chunkIndex, ManagedBuffer buffer) {
            buffer.retain();
            res.successChunks.add(chunkIndex);
            res.buffers.add(buffer);
            sem.release();
          }

          @Override
          public void onFailure(int chunkIndex, Throwable e) {
            res.failedChunks.add(chunkIndex);
            sem.release();
          }
        };

    for (int chunkIndex : chunkIndices) {
      client.fetchChunk(STREAM_ID, chunkIndex, 10000, callback);
    }
    if (!sem.tryAcquire(chunkIndices.size(), 5, TimeUnit.SECONDS)) {
      fail("Timeout getting response from the server");
    }
    client.close();
    return res;
  }

  @Test
  public void fetchBufferChunk() throws Exception {
    FetchResult res = fetchChunks(Arrays.asList(BUFFER_CHUNK_INDEX));
    assertEquals(Sets.newHashSet(BUFFER_CHUNK_INDEX), res.successChunks);
    assertTrue(res.failedChunks.isEmpty());
    assertBufferListsEqual(Arrays.asList(bufferChunk), res.buffers);
    res.releaseBuffers();
  }

  @Test
  public void fetchFileChunk() throws Exception {
    FetchResult res = fetchChunks(Arrays.asList(FILE_CHUNK_INDEX));
    assertEquals(Sets.newHashSet(FILE_CHUNK_INDEX), res.successChunks);
    assertTrue(res.failedChunks.isEmpty());
    assertBufferListsEqual(Arrays.asList(fileChunk), res.buffers);
    res.releaseBuffers();
  }

  @Test
  public void fetchNonExistentChunk() throws Exception {
    FetchResult res = fetchChunks(Arrays.asList(12345));
    assertTrue(res.successChunks.isEmpty());
    assertEquals(Sets.newHashSet(12345), res.failedChunks);
    assertTrue(res.buffers.isEmpty());
  }

  @Test
  public void fetchBothChunks() throws Exception {
    FetchResult res = fetchChunks(Arrays.asList(BUFFER_CHUNK_INDEX, FILE_CHUNK_INDEX));
    assertEquals(Sets.newHashSet(BUFFER_CHUNK_INDEX, FILE_CHUNK_INDEX), res.successChunks);
    assertTrue(res.failedChunks.isEmpty());
    assertBufferListsEqual(Arrays.asList(bufferChunk, fileChunk), res.buffers);
    res.releaseBuffers();
  }

  @Test
  public void fetchChunkAndNonExistent() throws Exception {
    FetchResult res = fetchChunks(Arrays.asList(BUFFER_CHUNK_INDEX, 12345));
    assertEquals(Sets.newHashSet(BUFFER_CHUNK_INDEX), res.successChunks);
    assertEquals(Sets.newHashSet(12345), res.failedChunks);
    assertBufferListsEqual(Arrays.asList(bufferChunk), res.buffers);
    res.releaseBuffers();
  }

  private static void assertBufferListsEqual(List<ManagedBuffer> list0, List<ManagedBuffer> list1)
      throws Exception {
    assertEquals(list0.size(), list1.size());
    for (int i = 0; i < list0.size(); i++) {
      assertBuffersEqual(list0.get(i), list1.get(i));
    }
  }

  private static void assertBuffersEqual(ManagedBuffer buffer0, ManagedBuffer buffer1)
      throws Exception {
    ByteBuffer nio0 = buffer0.nioByteBuffer();
    ByteBuffer nio1 = buffer1.nioByteBuffer();

    int len = nio0.remaining();
    assertEquals(nio0.remaining(), nio1.remaining());
    for (int i = 0; i < len; i++) {
      assertEquals(nio0.get(), nio1.get());
    }
  }
}
