//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This is an internal class which uses {@link IteratingCallback} used to flush websocket frames out to the {@link EndPoint}.</p>
 *
 * <p>Even though this uses an iterating callback, we need an {@link AutoLock} to synchronize between the iterating callback
 * and the {@link #timeoutExpired()} method, which will iterate through the {@link #_queue} and {@link #_entries} to find
 * expired entries.</p>
 */
public class FrameFlusher extends IteratingCallback
{
    public static final Frame FLUSH_FRAME = new Frame(OpCode.UNDEFINED)
    {
        @Override
        public boolean isControlFrame()
        {
            return true;
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(FrameFlusher.class);

    private final AutoLock _lock = new AutoLock();
    private final LongAdder _messagesOut = new LongAdder();
    private final LongAdder _bytesOut = new LongAdder();
    private final ByteBufferPool _bufferPool;
    private final EndPoint _endPoint;
    private final int _bufferSize;
    private final Generator _generator;
    private final int _maxGather;
    private final Deque<Entry> _queue = new ArrayDeque<>();
    private final CyclicTimeout _cyclicTimeout;
    private final List<Entry> _entries;
    private final List<RetainableByteBuffer> _releasableBuffers = new ArrayList<>();

    private boolean _timeoutPending = false;
    private RetainableByteBuffer _batchBuffer;
    private boolean _canEnqueue = true;
    private Throwable _closedCause;
    private long _frameTimeout;
    private long _messageTimeout;
    private boolean _useDirectByteBuffers;
    private long _currentMessageTimeout;

    public FrameFlusher(ByteBufferPool bufferPool, Scheduler scheduler, Generator generator, EndPoint endPoint, int bufferSize, int maxGather)
    {
        _bufferPool = bufferPool;
        _endPoint = endPoint;
        _bufferSize = bufferSize;
        _generator = Objects.requireNonNull(generator);
        _maxGather = maxGather;
        _entries = new ArrayList<>(maxGather);
        _cyclicTimeout = new CyclicTimeout(scheduler)
        {
            @Override
            public void onTimeoutExpired()
            {
                timeoutExpired();
            }
        };
    }

    public boolean isUseDirectByteBuffers()
    {
        return _useDirectByteBuffers;
    }

    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        this._useDirectByteBuffers = useDirectByteBuffers;
    }

    /**
     * Enqueue a Frame to be written to the endpoint.
     *
     * @param frame The frame to queue
     * @param callback The callback to call once the frame is sent
     * @param batch True if batch mode is to be used
     * @return returns true if the frame was enqueued and iterate needs to be called, returns false if the
     * FrameFlusher was closed
     */
    public boolean enqueue(Frame frame, Callback callback, boolean batch)
    {
        Entry entry = new Entry(frame, callback, batch);
        byte opCode = frame.getOpCode();

        Throwable error = null;
        Throwable abort = null;
        List<Entry> failedEntries = null;
        CloseStatus closeStatus = null;

        try (AutoLock l = _lock.lock())
        {
            if (!_canEnqueue || _closedCause != null)
            {
                error = (_closedCause == null) ? new ClosedChannelException() : _closedCause;
            }
            else
            {
                switch (opCode)
                {
                    case OpCode.CLOSE:
                        closeStatus = CloseStatus.getCloseStatus(frame);
                        if (closeStatus.isAbnormal())
                        {
                            //fail all existing entries in the queue, and enqueue the error close
                            failedEntries = new ArrayList<>(_queue);
                            _queue.clear();
                        }
                        _queue.offerLast(entry);
                        this._canEnqueue = false;
                        break;

                    case OpCode.PING:
                    case OpCode.PONG:
                        _queue.offerFirst(entry);
                        break;

                    default:
                        _queue.offerLast(entry);
                        break;
                }

                long currentTime = System.nanoTime();
                if (_frameTimeout > 0)
                    entry.setFrameExpiry(currentTime + _frameTimeout);
                if (_messageTimeout > 0)
                {
                    if (frame.isDataFrame())
                    {
                        // If this is the first frame of the message remember the message timeout.
                        if (frame.getOpCode() != OpCode.CONTINUATION)
                            _currentMessageTimeout = currentTime + TimeUnit.MILLISECONDS.toNanos(_messageTimeout);
                        else if (_currentMessageTimeout <= currentTime)
                            abort = new WebSocketWriteTimeoutException("FrameFlusher Message Write Timeout");

                        entry.setMessageExpiry(_currentMessageTimeout);
                    }
                    else
                    {
                        entry.setMessageExpiry(currentTime + TimeUnit.MILLISECONDS.toNanos(_messageTimeout));
                    }
                }

                /* When the timeout expires we will go over entries in the queue and entries list to see
                if any of them have expired, it will then reset the timeout for the frame with the soonest expiry time. */
                if ((_frameTimeout > 0 || _messageTimeout > 0) && !_timeoutPending && abort == null)
                {
                    _timeoutPending = true;
                    _cyclicTimeout.schedule(Math.min(_frameTimeout, _messageTimeout), TimeUnit.MILLISECONDS);
                }
            }
        }

        if (failedEntries != null)
        {
            WebSocketException failure =
                new WebSocketException(
                    "Flusher received abnormal CloseFrame: " +
                        CloseStatus.codeString(closeStatus.getCode()), closeStatus.getCause());

            for (Entry e : failedEntries)
            {
                notifyCallbackFailure(e.callback, failure);
            }
        }

        if (error != null)
        {
            notifyCallbackFailure(callback, error);
            return false;
        }

        if (abort != null)
        {
            abort(abort);
            return false;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Enqueued {} to {}", entry, this);
        return true;
    }

    public void onClose(Throwable cause)
    {
        try (AutoLock l = _lock.lock())
        {
            _closedCause = cause == null ? new ClosedChannelException() : cause;
        }
        abort(_closedCause);
    }

    @Override
    protected Action process() throws Throwable
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Flushing {}", this);

        List<Entry> entries = new ArrayList<>();
        boolean flush = false;
        try (AutoLock l = _lock.lock())
        {
            if (_closedCause != null)
                throw _closedCause;

            while (!_queue.isEmpty() && _entries.size() <= _maxGather)
            {
                Entry entry = _queue.poll();
                _entries.add(entry);
                if (entry.frame == FLUSH_FRAME)
                {
                    flush = true;
                    break;
                }
                else
                {
                    entries.add(entry);
                }
            }
        }

        // Process the entries outside the lock.
        boolean canBatch = true;
        List<ByteBuffer> buffers = new ArrayList<>((_maxGather * 2) + 1);
        if (_batchBuffer != null)
            buffers.add(_batchBuffer.getByteBuffer());
        for (Entry entry : entries)
        {
            _messagesOut.increment();

            int batchSpace = _batchBuffer == null ? _bufferSize : BufferUtil.space(_batchBuffer.getByteBuffer());
            boolean batch = canBatch && entry.batch &&
                !entry.frame.isControlFrame() &&
                entry.frame.getPayloadLength() < _bufferSize / 4 &&
                (batchSpace - Generator.MAX_HEADER_LENGTH) >= entry.frame.getPayloadLength();

            if (batch)
            {
                // Acquire a batchBuffer if we don't have one.
                if (_batchBuffer == null)
                {
                    _batchBuffer = acquireBuffer(_bufferSize);
                    buffers.add(_batchBuffer.getByteBuffer());
                }

                // Generate the frame into the batchBuffer.
                _generator.generateWholeFrame(entry.frame, _batchBuffer.getByteBuffer());
            }
            else
            {
                if (canBatch && _batchBuffer != null && batchSpace >= Generator.MAX_HEADER_LENGTH)
                {
                    // Use the batch space for our header.
                    _generator.generateHeader(entry.frame, _batchBuffer.getByteBuffer());
                }
                else
                {
                    // Add headers to the list of buffers.
                    RetainableByteBuffer headerBuffer = acquireBuffer(Generator.MAX_HEADER_LENGTH);
                    _releasableBuffers.add(headerBuffer);
                    _generator.generateHeader(entry.frame, headerBuffer.getByteBuffer());
                    buffers.add(headerBuffer.getByteBuffer());
                }

                // Add the payload to the list of buffers.
                ByteBuffer payload = entry.frame.getPayload();
                if (BufferUtil.hasContent(payload))
                {
                    if (entry.frame.isMasked())
                    {
                        RetainableByteBuffer masked = acquireBuffer(entry.frame.getPayloadLength());
                        payload = masked.getByteBuffer();
                        _releasableBuffers.add(masked);
                        _generator.generatePayload(entry.frame, payload);
                    }
                    buffers.add(payload.slice());
                }

                // Once we have added another buffer we cannot add to the batch buffer again.
                canBatch = false;
                flush = true;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} processed {} entries flush={}: {}",
                this,
                entries.size(),
                flush,
                entries);

        if (flush)
        {
            // If we're flushing we should release the batch buffer upon completion.
            if (_batchBuffer != null)
            {
                _releasableBuffers.add(_batchBuffer);
                _batchBuffer = null;
            }

            int i = 0;
            int bytes = 0;
            ByteBuffer[] bufferArray = new ByteBuffer[buffers.size()];
            for (ByteBuffer bb : buffers)
            {
                bytes += bb.limit() - bb.position();
                bufferArray[i++] = bb;
            }
            _bytesOut.add(bytes);
            _endPoint.write(this, bufferArray);
            buffers.clear();
        }
        else
        {
            // If we did not get any new entries go to IDLE state
            if (entries.isEmpty())
            {
                releaseAggregate();
                return Action.IDLE;
            }

            // We just aggregated the entries, so we need to succeed their callbacks.
            succeeded();
        }

        return Action.SCHEDULED;
    }

    private RetainableByteBuffer acquireBuffer(int capacity)
    {
        return _bufferPool.acquire(capacity, isUseDirectByteBuffers());
    }

    private int getQueueSize()
    {
        try (AutoLock l = _lock.lock())
        {
            return _queue.size();
        }
    }

    private void timeoutExpired()
    {
        Throwable failure = null;
        try (AutoLock l = _lock.lock())
        {
            if (_closedCause != null)
                return;

            long currentTime = NanoTime.now();
            long earliestExpiry = Long.MAX_VALUE;

            // Iterate through entries in both the queue and entries list, and if any entry has expired
            // then we fail the FrameFlusher, otherwise schedule a new timeout.
            Iterator<Entry> iterator = TypeUtil.concat(_entries.iterator(), _queue.iterator());
            while (iterator.hasNext())
            {
                Entry entry = iterator.next();

                // Check frame timeout.
                long frameExpiry = entry.getFrameExpiry();
                if (frameExpiry > 0)
                {
                    if (frameExpiry <= currentTime)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("FrameFlusher frame write timeout on entry: {}", entry);
                        failure = new WebSocketWriteTimeoutException("FrameFlusher Frame Write Timeout");
                        break;
                    }

                    if (frameExpiry < earliestExpiry)
                        earliestExpiry = frameExpiry;
                }

                // Check message timeout.
                long messageExpiry = entry.getMessageExpiry();
                if (messageExpiry > 0)
                {
                    if (messageExpiry <= currentTime)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("FrameFlusher message write timeout on entry: {}", entry);
                        failure = new WebSocketWriteTimeoutException("FrameFlusher Message Write Timeout");
                        break;
                    }

                    if (messageExpiry < earliestExpiry)
                        earliestExpiry = messageExpiry;
                }
            }

            // Try to schedule a new timeout for the next earliest expiry time.
            boolean rescheduleTimeout = (failure == null) && (earliestExpiry != Long.MAX_VALUE);
            if (rescheduleTimeout)
                _cyclicTimeout.schedule(earliestExpiry - currentTime, TimeUnit.MILLISECONDS);
            else
                _timeoutPending = false;
        }

        if (failure != null)
            abort(failure);
    }

    @Override
    protected void onSuccess()
    {
        List<Entry> succeededEntries;
        try (AutoLock l = _lock.lock())
        {
            succeededEntries = new ArrayList<>(_entries);
            _entries.clear();
        }

        for (Entry entry : succeededEntries)
        {
            if (entry.frame.getOpCode() == OpCode.CLOSE)
                _endPoint.shutdownOutput();
            notifyCallbackSuccess(entry.callback);
        }

        _releasableBuffers.forEach(RetainableByteBuffer::release);
        _releasableBuffers.clear();
        super.onSuccess();
    }

    @Override
    public void onCompleteFailure(Throwable failure)
    {
        if (_batchBuffer != null)
            _batchBuffer.clear();
        releaseAggregate();
        List<Entry> failedEntries;
        try (AutoLock l = _lock.lock())
        {
            // Ensure no more entries can be enqueued.
            _canEnqueue = false;
            if (_closedCause == null)
                _closedCause = failure;
            else if (_closedCause != failure)
                _closedCause.addSuppressed(failure);
            failedEntries = new ArrayList<>(_queue);
            failedEntries.addAll(_entries);
            _queue.clear();
            _entries.clear();
        }

        for (Entry entry : failedEntries)
        {
            notifyCallbackFailure(entry.callback, failure);
        }

        _releasableBuffers.forEach(RetainableByteBuffer::release);
        _releasableBuffers.clear();
        _endPoint.close(_closedCause);
    }

    private void releaseAggregate()
    {
        if (_batchBuffer != null && !_batchBuffer.hasRemaining())
        {
            _batchBuffer.release();
            _batchBuffer = null;
        }
    }

    protected void notifyCallbackSuccess(Callback callback)
    {
        try
        {
            if (callback != null)
            {
                callback.succeeded();
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying success of callback {}", callback, x);
        }
    }

    protected void notifyCallbackFailure(Callback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
            {
                callback.failed(failure);
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying failure of callback {}", callback, x);
        }
    }

    /**
     * @deprecated use {@link #setFrameWriteTimeout(long)} or {@link #setMessageWriteTimeout(long)}.
     */
    @Deprecated
    public void setIdleTimeout(long idleTimeout)
    {
        _frameTimeout = idleTimeout;
    }

    @Deprecated
    public long getIdleTimeout()
    {
        return _frameTimeout;
    }

    public void setFrameWriteTimeout(long writeTimeout)
    {
        _frameTimeout = writeTimeout;
    }

    public void setMessageWriteTimeout(long writeTimeout)
    {
        _messageTimeout = writeTimeout;
    }

    public long getMessagesOut()
    {
        return _messagesOut.longValue();
    }

    public long getBytesOut()
    {
        return _bytesOut.longValue();
    }

    @Override
    public String toString()
    {
        return String.format("%s[queueSize=%d,aggregate=%s]",
            super.toString(),
            getQueueSize(),
            _batchBuffer);
    }

    private static class Entry extends FrameEntry
    {
        private long _messageExpiry = -1;
        private long _frameExpiry = -1;

        private Entry(Frame frame, Callback callback, boolean batch)
        {
            super(frame, callback, batch);
        }

        public long getFrameExpiry()
        {
            return _frameExpiry;
        }

        public void setFrameExpiry(long frameExpiry)
        {
            _frameExpiry = frameExpiry;
        }

        public void setMessageExpiry(long messageExpiry)
        {
            _messageExpiry = messageExpiry;
        }

        public long getMessageExpiry()
        {
            return _messageExpiry;
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s,%s,%b}", getClass().getSimpleName(), frame, callback, batch);
        }
    }
}
