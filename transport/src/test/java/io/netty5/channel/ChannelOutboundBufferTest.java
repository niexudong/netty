/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.CompositeBuffer;
import io.netty5.channel.ChannelOutboundBuffer.MessageProcessor;
import io.netty5.util.CharsetUtil;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.SingleThreadEventExecutor;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.netty.buffer.Unpooled.compositeBuffer;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.directBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelOutboundBufferTest {

    private static void testChannelOutboundBuffer(BiConsumer<ChannelOutboundBuffer, EventExecutor> testConsumer)
            throws InterruptedException {
        EventExecutor executor = new SingleThreadEventExecutor();
        try {
            ChannelOutboundBuffer buffer = new ChannelOutboundBuffer(executor);
            executor.submit(() -> testConsumer.accept(buffer, executor)).sync();
        } finally {
            executor.shutdownGracefully();
        }
    }

    @Test
    public void testEmptyNioBuffers() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            assertEquals(0, buffer.nioBufferCount());
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertNotNull(buffers);
            for (ByteBuffer b: buffers) {
                assertNull(b);
            }
            assertEquals(0, buffer.nioBufferCount());
            release(buffer);
        });
    }

    @Test
    public void flushingEmptyBuffers() throws Exception {
        testChannelOutboundBuffer((buffer, executor) -> {
            Buffer buf = BufferAllocator.onHeapUnpooled().allocate(0);
            buffer.addMessage(buf, 0, executor.newPromise());
            buffer.addFlush();
            AtomicInteger messageCounter = new AtomicInteger();
            MessageProcessor<RuntimeException> messageProcessor = msg -> {
                assertNotNull(msg);
                messageCounter.incrementAndGet();
                return true;
            };
            buffer.forEachFlushedMessage(messageProcessor);
            assertThat(messageCounter.get()).isOne();
            buffer.removeBytes(0); // This must remove the empty buffer.
            messageCounter.set(0);
            buffer.forEachFlushedMessage(messageProcessor);
            assertThat(messageCounter.get()).isZero();
        });
    }

    @Test
    public void testNioBuffersSingleBackedByteBuf() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            assertEquals(0, buffer.nioBufferCount());

            ByteBuf buf = copiedBuffer("buf1", CharsetUtil.US_ASCII);
            ByteBuffer nioBuf = buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes());
            buffer.addMessage(buf, buf.readableBytes(), executor.newPromise());
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertNotNull(buffers);
            assertEquals(1, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                if (i == 0) {
                    assertEquals(buffers[0], nioBuf);
                } else {
                    assertNull(buffers[i]);
                }
            }
            release(buffer);
        });
    }

    @Test
    public void testNioBuffersSingleBacked() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            assertEquals(0, buffer.nioBufferCount());

            Buffer buf = BufferAllocator.onHeapUnpooled().copyOf("buf1", CharsetUtil.US_ASCII);
            buffer.addMessage(buf, buf.readableBytes(), executor.newPromise());
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertNotNull(buffers);
            assertEquals(1, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                if (i == 0) {
                    assertEquals(1, buf.countReadableComponents());
                    buf.forEachReadable(0, (index, component) -> {
                        assertEquals(0, index, "Expected buffer to only have a single component.");
                        assertEquals(buffers[0], component.readableBuffer());
                        return true;
                    });
                } else {
                    assertNull(buffers[i]);
                }
            }
            release(buffer);
        });
    }

    @Test
    public void testNioBuffersExpandByteBuf() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            ByteBuf buf = directBuffer().writeBytes("buf1".getBytes(CharsetUtil.US_ASCII));
            for (int i = 0; i < 64; i++) {
                buffer.addMessage(buf.copy(), buf.readableBytes(), executor.newPromise());
            }
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertEquals(64, buffer.nioBufferCount());
            for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                assertEquals(buffers[i], buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes()));
            }
            release(buffer);
            buf.release();
        });
    }

    @Test
    public void testNioBuffersExpand() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            Buffer buf = BufferAllocator.offHeapUnpooled().copyOf("buf1", CharsetUtil.US_ASCII);
            for (int i = 0; i < 64; i++) {
                buffer.addMessage(buf.copy(), buf.readableBytes(), executor.newPromise());
            }
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertEquals(64, buffer.nioBufferCount());
            assertEquals(1, buf.countReadableComponents());
            buf.forEachReadable(0, (index, component) -> {
                assertEquals(0, index);
                ByteBuffer expected = component.readableBuffer();
                for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                    assertEquals(expected, buffers[i]);
                }
                return true;
            });

            release(buffer);
            buf.close();
        });
    }

    @Test
    public void testNioBuffersExpand2ByteBuf() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            CompositeByteBuf comp = compositeBuffer(256);
            ByteBuf buf = directBuffer().writeBytes("buf1".getBytes(CharsetUtil.US_ASCII));
            for (int i = 0; i < 65; i++) {
                comp.addComponent(true, buf.copy());
            }
            buffer.addMessage(comp, comp.readableBytes(), executor.newPromise());

            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertEquals(65, buffer.nioBufferCount());
            for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                if (i < 65) {
                    assertEquals(buffers[i], buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes()));
                } else {
                    assertNull(buffers[i]);
                }
            }
            release(buffer);
            buf.release();
        });
    }

    @Test
    public void testNioBuffersExpand2() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            Buffer buf = BufferAllocator.offHeapUnpooled().copyOf("buf1", CharsetUtil.US_ASCII);
            @SuppressWarnings("unchecked")
            var sends = Stream.generate(() -> buf.copy().send()).limit(65).collect(Collectors.toList());
            CompositeBuffer comp = BufferAllocator.offHeapUnpooled().compose(sends);

            buffer.addMessage(comp, comp.readableBytes(), executor.newPromise());

            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            ByteBuffer[] buffers = buffer.nioBuffers();
            assertEquals(65, buffer.nioBufferCount());
            assertEquals(1, buf.countReadableComponents());
            buf.forEachReadable(0, (index, component) -> {
                assertEquals(0, index);
                ByteBuffer expected = component.readableBuffer();
                for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                    if (i < 65) {
                        assertEquals(expected, buffers[i]);
                    } else {
                        assertNull(buffers[i]);
                    }
                }
                return true;
            });

            release(buffer);
            buf.close();
        });
    }

    @Test
    public void testNioBuffersMaxCountByteBuf() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            CompositeByteBuf comp = compositeBuffer(256);
            ByteBuf buf = directBuffer().writeBytes("buf1".getBytes(CharsetUtil.US_ASCII));
            for (int i = 0; i < 65; i++) {
                comp.addComponent(true, buf.copy());
            }
            assertEquals(65, comp.nioBufferCount());
            buffer.addMessage(comp, comp.readableBytes(), executor.newPromise());
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            final int maxCount = 10;    // less than comp.nioBufferCount()
            ByteBuffer[] buffers = buffer.nioBuffers(maxCount, Integer.MAX_VALUE);
            assertTrue(buffer.nioBufferCount() <= maxCount, "Should not be greater than maxCount");
            for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                assertEquals(buffers[i], buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes()));
            }
            release(buffer);
            buf.release();
        });
    }

    @Test
    public void testNioBuffersMaxCount() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            Buffer buf = BufferAllocator.offHeapUnpooled().copyOf("buf1", CharsetUtil.US_ASCII);
            assertEquals(4, buf.readableBytes());
            @SuppressWarnings("unchecked")
            var sends = Stream.generate(() -> buf.copy().send()).limit(65).collect(Collectors.toList());
            CompositeBuffer comp = BufferAllocator.offHeapUnpooled().compose(sends);

            assertEquals(65, comp.countComponents());
            buffer.addMessage(comp, comp.readableBytes(), executor.newPromise());
            assertEquals(0, buffer.nioBufferCount(), "Should still be 0 as not flushed yet");
            buffer.addFlush();
            final int maxCount = 10;    // less than comp.nioBufferCount()
            ByteBuffer[] buffers = buffer.nioBuffers(maxCount, Integer.MAX_VALUE);
            assertTrue(buffer.nioBufferCount() <= maxCount, "Should not be greater than maxCount");
            buf.forEachReadable(0, (index, component) -> {
                assertEquals(0, index);
                ByteBuffer expected = component.readableBuffer();
                for (int i = 0;  i < buffer.nioBufferCount(); i++) {
                    assertEquals(expected, buffers[i]);
                }
                return true;
            });
            release(buffer);
            buf.close();
        });
    }

    @Test
    public void removeBytesByteBuf() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            ByteBuf buf = directBuffer().writeBytes("buf1".getBytes(CharsetUtil.US_ASCII));
            int size = buf.readableBytes();
            buffer.addMessage(buf, size, executor.newPromise());
            buffer.addFlush();
            assertEquals(0, buffer.currentProgress());
            buffer.removeBytes(size / 2);
            assertEquals(size / 2, buffer.currentProgress());
            assertThat(buffer.current()).isNotNull();
            buffer.removeBytes(size);
            assertNull(buffer.current());
            assertTrue(buffer.isEmpty());
            assertEquals(0, buffer.currentProgress());
        });
    }

    @Test
    public void removeBytes() throws InterruptedException {
        testChannelOutboundBuffer((buffer, executor) -> {
            Buffer buf = BufferAllocator.onHeapUnpooled().copyOf("buf1", CharsetUtil.US_ASCII);
            int size = buf.readableBytes();
            buffer.addMessage(buf, size, executor.newPromise());
            buffer.addFlush();
            assertEquals(0, buffer.currentProgress());
            buffer.removeBytes(size / 2);
            assertEquals(size / 2, buffer.currentProgress());
            assertThat(buffer.current()).isNotNull();
            buffer.removeBytes(size);
            assertNull(buffer.current());
            assertTrue(buffer.isEmpty());
            assertEquals(0, buffer.currentProgress());
        });
    }

    private static void release(ChannelOutboundBuffer buffer) {
        for (;;) {
            if (!buffer.remove()) {
                break;
            }
        }
    }
}
