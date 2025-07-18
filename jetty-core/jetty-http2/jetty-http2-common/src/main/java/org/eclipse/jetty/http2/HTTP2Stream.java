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

package org.eclipse.jetty.http2;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.WritePendingException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.FailureFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.StreamFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Stream implements Stream, Attachable, Closeable, Callback, Dumpable, CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Stream.class);

    private final AutoLock lock = new AutoLock();
    private final Deque<Data> dataQueue = new ArrayDeque<>(1);
    private final AtomicReference<Object> attachment = new AtomicReference<>();
    private final AtomicReference<ConcurrentMap<String, Object>> attributes = new AtomicReference<>();
    private final AtomicReference<CloseState> closeState = new AtomicReference<>(CloseState.NOT_CLOSED);
    private final AtomicInteger sendWindow = new AtomicInteger();
    private final AtomicInteger recvWindow = new AtomicInteger();
    private final long creationNanoTime = NanoTime.now();
    private final HTTP2Session session;
    private final int streamId;
    private final MetaData.Request request;
    private final boolean local;
    private Callback sendCallback;
    private Throwable failure;
    private boolean localReset;
    private boolean remoteReset;
    private Listener listener;
    private long dataLength;
    private boolean dataDemand;
    private boolean dataStalled;
    private boolean committed;
    private long idleTimeout;
    private long expireNanoTime = Long.MAX_VALUE;

    public HTTP2Stream(HTTP2Session session, int streamId, MetaData.Request request, boolean local)
    {
        this.session = session;
        this.streamId = streamId;
        this.request = request;
        this.local = local;
        this.dataLength = -1;
        this.dataStalled = true;
    }

    @Override
    public int getId()
    {
        return streamId;
    }

    @Override
    public boolean equals(Object o)
    {
        return this == o;
    }

    @Override
    public int hashCode()
    {
        return streamId;
    }

    @Override
    public Object getAttachment()
    {
        return attachment.get();
    }

    @Override
    public void setAttachment(Object attachment)
    {
        this.attachment.set(attachment);
    }

    @Override
    public boolean isLocal()
    {
        return local;
    }

    @Override
    public HTTP2Session getSession()
    {
        return session;
    }

    @Override
    public void headers(HeadersFrame frame, Callback callback)
    {
        send(new FrameList(frame), callback);
    }

    public void send(FrameList frameList, Callback callback)
    {
        if (startWrite(callback))
            session.frames(this, frameList.getFrames(), this);
    }

    @Override
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener)
    {
        session.push(this, promise, frame, listener);
    }

    @Override
    public void data(DataFrame frame, Callback callback)
    {
        if (startWrite(callback))
            session.data(this, frame, this);
    }

    @Override
    public void reset(ResetFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Resetting {} {}", frame, this);

        int flowControlLength;
        boolean reset = true;
        Throwable resetFailure = null;
        try (AutoLock ignored = lock.lock())
        {
            flowControlLength = drain();
            if (localReset)
            {
                reset = false;
                resetFailure = failure;
            }
            else
            {
                localReset = true;
                failure = new EOFException("reset");
            }
        }

        session.dataConsumed(this, flowControlLength);

        if (reset)
        {
            session.reset(this, frame, callback);
        }
        else
        {
            close();
            session.removeStream(this);
            if (resetFailure == null)
                callback.succeeded();
            else
                callback.failed(resetFailure);
        }
    }

    private boolean startWrite(Callback callback)
    {
        Throwable failure;
        try (AutoLock ignored = lock.lock())
        {
            failure = this.failure;
            if (failure == null && sendCallback == null)
            {
                sendCallback = callback;
                return true;
            }
        }
        if (failure == null)
            failure = new WritePendingException();
        callback.failed(failure);
        return false;
    }

    @Override
    public Object getAttribute(String key)
    {
        return attributes().get(key);
    }

    @Override
    public void setAttribute(String key, Object value)
    {
        attributes().put(key, value);
    }

    @Override
    public Object removeAttribute(String key)
    {
        return attributes().remove(key);
    }

    @Override
    public boolean isReset()
    {
        try (AutoLock ignored = lock.lock())
        {
            return localReset || remoteReset;
        }
    }

    private boolean isFailed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return failure != null;
        }
    }

    public boolean isResetOrFailed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return isReset() || isFailed();
        }
    }

    @Override
    public boolean isClosed()
    {
        return closeState.get() == CloseState.CLOSED;
    }

    @Override
    public boolean isRemotelyClosed()
    {
        CloseState state = closeState.get();
        return state == CloseState.REMOTELY_CLOSED || state == CloseState.CLOSING || state == CloseState.CLOSED;
    }

    public boolean isLocallyClosed()
    {
        return closeState.get() == CloseState.LOCALLY_CLOSED;
    }

    public void commit()
    {
        committed = true;
    }

    public boolean isCommitted()
    {
        return committed;
    }

    public boolean isOpen()
    {
        return !isClosed();
    }

    public void notIdle()
    {
        long idleTimeout = getIdleTimeout();
        if (idleTimeout > 0)
            expireNanoTime = NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(idleTimeout);
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    @Override
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
        notIdle();
        session.scheduleTimeout(this);
    }

    protected void onIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Idle timeout {}ms expired on {}", getIdleTimeout(), this);

        // Notify the application.
        notifyIdleTimeout(this, timeout, Promise.from(timedOut ->
        {
            if (timedOut)
                reset(new ResetFrame(getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            else
                notIdle();
        }, x -> reset(new ResetFrame(getId(), ErrorCode.INTERNAL_ERROR.code), Callback.NOOP)));
    }

    private ConcurrentMap<String, Object> attributes()
    {
        ConcurrentMap<String, Object> map = attributes.get();
        if (map == null)
        {
            map = new ConcurrentHashMap<>();
            if (!attributes.compareAndSet(null, map))
            {
                map = attributes.get();
            }
        }
        return map;
    }

    @Override
    public Listener getListener()
    {
        return listener;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
        if (listener == null)
            demand();
    }

    public void process(Frame frame, Callback callback)
    {
        notIdle();
        switch (frame.getType())
        {
            case PREFACE -> onNewStream(callback);
            case HEADERS -> onHeaders((HeadersFrame)frame, callback);
            case RST_STREAM -> onReset((ResetFrame)frame, callback);
            case PUSH_PROMISE -> onPush((PushPromiseFrame)frame, callback);
            case WINDOW_UPDATE -> onWindowUpdate((WindowUpdateFrame)frame, callback);
            case FAILURE -> onFailure((FailureFrame)frame, callback);
            default -> throw new UnsupportedOperationException();
        }
    }

    public void process(Data data)
    {
        notIdle();
        onData(data);
    }

    private void onNewStream(Callback callback)
    {
        notifyNewStream(this);
        callback.succeeded();
    }

    private void onHeaders(HeadersFrame frame, Callback callback)
    {
        boolean offered = false;
        MetaData metaData = frame.getMetaData();
        boolean isTrailer = !metaData.isRequest() && !metaData.isResponse();
        if (isTrailer)
        {
            // In case of trailers, notify first and then offer EOF to
            // avoid race conditions due to concurrent calls to readData().
            boolean closed = updateClose(true, CloseState.Event.RECEIVED);
            notifyHeaders(this, frame);
            if (closed)
                getSession().removeStream(this);
            // Offer EOF in case the application calls readData() or demand().
            offered = offer(Data.eof(getId()));
        }
        else
        {
            HttpFields fields = metaData.getHttpFields();
            long length = -1;
            if (fields != null && !HttpMethod.CONNECT.is(request.getMethod()))
                length = fields.getLongField(HttpHeader.CONTENT_LENGTH);
            dataLength = length;

            if (frame.isEndStream())
            {
                // Offer EOF for either the request or the response in
                // case the application calls readData() or demand().
                offered = offer(Data.eof(getId()));
            }

            // Requests are notified to a Session.Listener, here only notify responses.
            if (metaData.isResponse())
            {
                boolean closed = updateClose(frame.isEndStream(), CloseState.Event.RECEIVED);
                notifyHeaders(this, frame);
                if (closed)
                    getSession().removeStream(this);
            }
        }

        if (offered)
            processData();

        callback.succeeded();
    }

    private void onData(Data data)
    {
        DataFrame frame = data.frame();

        // SPEC: remotely closed streams must be replied with a reset.
        if (isRemotelyClosed())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Data {} for already closed {}", data, this);
            session.dataConsumed(this, data.frame().flowControlLength());
            reset(new ResetFrame(streamId, ErrorCode.STREAM_CLOSED_ERROR.code), Callback.NOOP);
            return;
        }

        if (dataLength >= 0)
        {
            dataLength -= frame.remaining();
            if (dataLength < 0 || (frame.isEndStream() && dataLength != 0))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Invalid data length {} for {}", data, this);
                session.dataConsumed(this, data.frame().flowControlLength());
                reset(new ResetFrame(streamId, ErrorCode.PROTOCOL_ERROR.code), Callback.NOOP);
                return;
            }
        }

        if (offer(data))
            processData();
    }

    private boolean offer(Data data)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (failure == null)
            {
                boolean process = dataQueue.isEmpty() && dataDemand;
                // Retain the data because it is stored for later use.
                data.retain();
                dataQueue.offer(data);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data {} notifying onDataAvailable() {} for {}", data, process, this);
                return process;
            }
        }

        // Drop the frame.
        if (LOG.isDebugEnabled())
            LOG.debug("Data {} dropped for already reset/failed {}", data, this);
        session.dataConsumed(this, data.frame().flowControlLength());
        return false;
    }

    @Override
    public Data readData()
    {
        Data data;
        try (AutoLock ignored = lock.lock())
        {
            if (dataQueue.isEmpty())
                return null;
            data = dataQueue.poll();
            if (data.frame().isEndStream())
                dataQueue.offer(Data.eof(getId()));
        }

        if (updateClose(data.frame().isEndStream(), CloseState.Event.RECEIVED))
            session.removeStream(this);

        if (LOG.isDebugEnabled())
            LOG.debug("Reading {} for {}", data, this);

        notIdle();

        // Enlarge the flow control window now, since the application
        // may want to retain the Data objects, accumulating them in
        // memory beyond the flow control window, without copying them.
        session.dataConsumed(this, data.frame().flowControlLength());

        return data;
    }

    @Override
    public void demand()
    {
        boolean process = false;
        try (AutoLock ignored = lock.lock())
        {
            dataDemand = true;
            if (dataStalled && !dataQueue.isEmpty())
            {
                dataStalled = false;
                process = true;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Demand, {} data processing for {}", process ? "proceeding" : "stalling", this);
        if (process)
            processData();
    }

    public void processData()
    {
        while (true)
        {
            try (AutoLock ignored = lock.lock())
            {
                if (dataQueue.isEmpty() || !dataDemand)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Stalling data processing for {}", this);
                    dataStalled = true;
                    return;
                }
                dataDemand = false;
                dataStalled = false;
            }
            notifyDataAvailable(this);
        }
    }

    private boolean hasDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataDemand;
        }
    }

    private int dataSize()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataQueue.size();
        }
    }

    public long getDataLength()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataQueue.stream()
                .mapToLong(data -> data.frame().remaining())
                .sum();
        }
    }

    private void onReset(ResetFrame frame, Callback callback)
    {
        int flowControlLength;
        try (AutoLock ignored = lock.lock())
        {
            remoteReset = true;
            failure = new EofException("reset");
            flowControlLength = drain();
        }
        close();
        boolean removed = session.removeStream(this);
        session.dataConsumed(this, flowControlLength);
        if (removed)
            notifyReset(this, frame, callback);
        else
            callback.succeeded();
    }

    private void onPush(PushPromiseFrame frame, Callback callback)
    {
        // Pushed streams are implicitly locally closed.
        // They are closed when receiving an end-stream DATA frame.
        updateClose(true, CloseState.Event.AFTER_SEND);
        callback.succeeded();
    }

    private void onWindowUpdate(WindowUpdateFrame frame, Callback callback)
    {
        callback.succeeded();
    }

    private void onFailure(FailureFrame frame, Callback callback)
    {
        int flowControlLength;
        try (AutoLock ignored = lock.lock())
        {
            failure = frame.getFailure();
            flowControlLength = drain();
        }
        session.dataConsumed(this, flowControlLength);
        close();

        notifyFailure(this, frame, new Nested(callback)
        {
            @Override
            public void completed()
            {
                session.removeStream(HTTP2Stream.this);
            }
        });
    }

    private int drain()
    {
        assert lock.isHeldByCurrentThread();
        int length = 0;
        while (true)
        {
            Data data = dataQueue.poll();
            if (data == null)
                break;
            data.release();
            DataFrame frame = data.frame();
            length += frame.flowControlLength();
            if (frame.isEndStream())
            {
                dataQueue.offer(Data.eof(getId()));
                break;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Drained {} bytes for {}", length, this);
        return length;
    }

    public boolean updateClose(boolean update, CloseState.Event event)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Update close for {} update={} event={}", this, update, event);

        if (!update)
            return false;

        return switch (event)
        {
            case RECEIVED -> updateCloseAfterReceived();
            case BEFORE_SEND -> updateCloseBeforeSend();
            case AFTER_SEND -> updateCloseAfterSend();
        };
    }

    private boolean updateCloseAfterReceived()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED ->
                {
                    if (closeState.compareAndSet(current, CloseState.REMOTELY_CLOSED))
                        return false;
                }
                case LOCALLY_CLOSING ->
                {
                    if (closeState.compareAndSet(current, CloseState.CLOSING))
                    {
                        updateStreamCount(0, 1);
                        return false;
                    }
                }
                case LOCALLY_CLOSED ->
                {
                    close();
                    return true;
                }
                default ->
                {
                    return false;
                }
            }
        }
    }

    private boolean updateCloseBeforeSend()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED ->
                {
                    if (closeState.compareAndSet(current, CloseState.LOCALLY_CLOSING))
                        return false;
                }
                case REMOTELY_CLOSED ->
                {
                    if (closeState.compareAndSet(current, CloseState.CLOSING))
                    {
                        updateStreamCount(0, 1);
                        return false;
                    }
                }
                default ->
                {
                    return false;
                }
            }
        }
    }

    private boolean updateCloseAfterSend()
    {
        while (true)
        {
            CloseState current = closeState.get();
            switch (current)
            {
                case NOT_CLOSED, LOCALLY_CLOSING ->
                {
                    if (closeState.compareAndSet(current, CloseState.LOCALLY_CLOSED))
                        return false;
                }
                case REMOTELY_CLOSED, CLOSING ->
                {
                    close();
                    return true;
                }
                default ->
                {
                    return false;
                }
            }
        }
    }

    public int getSendWindow()
    {
        return sendWindow.get();
    }

    public int getRecvWindow()
    {
        return recvWindow.get();
    }

    public int updateSendWindow(int delta)
    {
        return sendWindow.getAndAdd(delta);
    }

    public int updateRecvWindow(int delta)
    {
        return recvWindow.getAndAdd(delta);
    }

    @Override
    public void close()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Close for {}", this);
        CloseState oldState = closeState.getAndSet(CloseState.CLOSED);
        if (oldState != CloseState.CLOSED)
        {
            int deltaClosing = oldState == CloseState.CLOSING ? -1 : 0;
            updateStreamCount(-1, deltaClosing);
            onClose();
        }
    }

    public void onClose()
    {
        notifyClosed(this);
    }

    private void updateStreamCount(int deltaStream, int deltaClosing)
    {
        session.updateStreamCount(isLocal(), deltaStream, deltaClosing);
    }

    @Override
    public void succeeded()
    {
        Callback callback = endWrite();
        if (callback != null)
            callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        Callback callback = endWrite();
        if (callback != null)
            callback.failed(x);
    }

    @Override
    public InvocationType getInvocationType()
    {
        try (AutoLock ignored = lock.lock())
        {
            return sendCallback != null ? sendCallback.getInvocationType() : Callback.super.getInvocationType();
        }
    }

    private Callback endWrite()
    {
        try (AutoLock ignored = lock.lock())
        {
            Callback callback = sendCallback;
            sendCallback = null;
            return callback;
        }
    }

    private void notifyNewStream(Stream stream)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onNewStream(stream);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
            }
        }
    }

    protected void notifyHeaders(Stream stream, HeadersFrame frame)
    {
        Stream.Listener listener = stream.getListener();
        if (listener == null)
            return;
        try
        {
            listener.onHeaders(stream, frame);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    private void notifyDataAvailable(Stream stream)
    {
        Listener listener = Objects.requireNonNullElse(this.listener, Listener.AUTO_DISCARD);
        try
        {
            listener.onDataAvailable(stream);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    private void notifyReset(Stream stream, ResetFrame frame, Callback callback)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onReset(stream, frame, callback);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                callback.failed(x);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private void notifyIdleTimeout(Stream stream, TimeoutException failure, Promise<Boolean> promise)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onIdleTimeout(stream, failure, promise);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                promise.failed(x);
            }
        }
        else
        {
            promise.succeeded(true);
        }
    }

    private void notifyFailure(Stream stream, FailureFrame frame, Callback callback)
    {
        Listener listener = this.listener;
        if (listener != null)
        {
            try
            {
                listener.onFailure(stream, frame.getError(), frame.getReason(), frame.getFailure(), callback);
            }
            catch (Throwable x)
            {
                LOG.info("Failure while notifying listener {}", listener, x);
                callback.failed(x);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private void notifyClosed(Stream stream)
    {
        Listener listener = this.listener;
        if (listener == null)
            return;
        try
        {
            listener.onClosed(stream);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d@%x{sendWindow=%s,recvWindow=%s,queue=%d,demand=%b,reset=%b/%b,%s,age=%d,request=%s,attachment=%s}",
            getClass().getSimpleName(),
            getId(),
            session.hashCode(),
            sendWindow,
            recvWindow,
            dataSize(),
            hasDemand(),
            localReset,
            remoteReset,
            closeState,
            NanoTime.millisSince(creationNanoTime),
            request,
            attachment);
    }

    /**
     * <p>An ordered list of frames belonging to the same stream.</p>
     */
    public static class FrameList
    {
        private final List<StreamFrame> frames;

        /**
         * <p>Creates a frame list of just the given HEADERS frame.</p>
         *
         * @param headers the HEADERS frame
         */
        public FrameList(HeadersFrame headers)
        {
            Objects.requireNonNull(headers);
            this.frames = List.of(headers);
        }

        /**
         * <p>Creates a frame list of the given frames.</p>
         *
         * @param headers the HEADERS frame for the headers
         * @param data the DATA frame for the content, or null if there is no content
         * @param trailers the HEADERS frame for the trailers, or null if there are no trailers
         */
        public FrameList(HeadersFrame headers, DataFrame data, HeadersFrame trailers)
        {
            Objects.requireNonNull(headers);
            ArrayList<StreamFrame> frames = new ArrayList<>(3);
            int streamId = headers.getStreamId();
            if (data != null && data.getStreamId() != streamId)
                throw new IllegalArgumentException("Invalid stream ID for DATA frame " + data);
            if (trailers != null && trailers.getStreamId() != streamId)
                throw new IllegalArgumentException("Invalid stream ID for HEADERS frame " + trailers);
            frames.add(headers);
            if (data != null)
                frames.add(data);
            if (trailers != null)
                frames.add(trailers);
            this.frames = Collections.unmodifiableList(frames);
        }

        /**
         * @return the stream ID of the frames in this list
         */
        public int getStreamId()
        {
            return frames.get(0).getStreamId();
        }

        /**
         * @return a List of non-null frames
         */
        public List<StreamFrame> getFrames()
        {
            return frames;
        }
    }
}
