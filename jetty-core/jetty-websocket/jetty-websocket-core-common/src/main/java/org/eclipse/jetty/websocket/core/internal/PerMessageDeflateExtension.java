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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.util.DemandChain;
import org.eclipse.jetty.websocket.core.util.DemandingFlusher;
import org.eclipse.jetty.websocket.core.util.TransformingFlusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per Message Deflate Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/rfc7692">Compression Extensions for WebSocket</a>
 */
public class PerMessageDeflateExtension extends AbstractExtension implements DemandChain
{
    private static final byte[] TAIL_BYTES = new byte[]{0x00, 0x00, (byte)0xFF, (byte)0xFF};
    private static final ByteBuffer TAIL_BYTES_BUF = ByteBuffer.wrap(TAIL_BYTES);
    private static final Logger LOG = LoggerFactory.getLogger(PerMessageDeflateExtension.class);
    private static final int DEFAULT_BUF_SIZE = 8 * 1024;

    private final OutgoingFlusher outgoingFlusher;
    private final IncomingFlusher incomingFlusher;
    private DeflaterPool.Entry deflaterHolder;
    private InflaterPool.Entry inflaterHolder;
    private boolean incomingCompressed;

    private ExtensionConfig configRequested;
    private ExtensionConfig configNegotiated;
    private int deflateBufferSize = DEFAULT_BUF_SIZE;
    private int inflateBufferSize = DEFAULT_BUF_SIZE;
    private boolean incomingContextTakeover = true;
    private boolean outgoingContextTakeover = true;

    public PerMessageDeflateExtension()
    {
        outgoingFlusher = new OutgoingFlusher();
        incomingFlusher = new IncomingFlusher();
    }

    @Override
    public String getName()
    {
        return "permessage-deflate";
    }

    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        // Compressed frames may increase in size so we need the flusher to fragment them.
        outgoingFlusher.sendFrame(frame, callback, batch);
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        incomingFlusher.onFrame(frame, callback);
    }

    @Override
    public void init(final ExtensionConfig config, WebSocketComponents components)
    {
        configRequested = new ExtensionConfig(config);
        Map<String, String> paramsNegotiated = new HashMap<>();

        for (String key : config.getParameterKeys())
        {
            key = key.trim();
            switch (key)
            {
                case "client_max_window_bits":
                case "server_max_window_bits":
                {
                    // Not supported by Jetty
                    // Don't negotiate these parameters
                    break;
                }
                case "client_no_context_takeover":
                {
                    paramsNegotiated.put("client_no_context_takeover", null);
                    incomingContextTakeover = false;
                    break;
                }
                case "server_no_context_takeover":
                {
                    paramsNegotiated.put("server_no_context_takeover", null);
                    outgoingContextTakeover = false;
                    break;
                }
                case "@deflate_buffer_size":
                {
                    deflateBufferSize = config.getParameter(key, DEFAULT_BUF_SIZE);
                    break;
                }
                case "@inflate_buffer_size":
                {
                    inflateBufferSize = config.getParameter(key, DEFAULT_BUF_SIZE);
                    break;
                }
                default:
                {
                    throw new IllegalArgumentException();
                }
            }
        }

        configNegotiated = new ExtensionConfig(config.getName(), paramsNegotiated);
        LOG.debug("config: outgoingContextTakover={}, incomingContextTakeover={} : {}", outgoingContextTakeover, incomingContextTakeover, this);

        super.init(configNegotiated, components);
    }

    @Override
    public void close()
    {
        incomingFlusher.closeFlusher();
        outgoingFlusher.closeFlusher();
        releaseInflater();
        releaseDeflater();
    }

    private static String toDetail(Inflater inflater)
    {
        return String.format("Inflater[finished=%b,read=%d,written=%d,remaining=%d,in=%d,out=%d]", inflater.finished(), inflater.getBytesRead(),
            inflater.getBytesWritten(), inflater.getRemaining(), inflater.getTotalIn(), inflater.getTotalOut());
    }

    private static String toDetail(Deflater deflater)
    {
        return String.format("Deflater[finished=%b,read=%d,written=%d,in=%d,out=%d]", deflater.finished(), deflater.getBytesRead(), deflater.getBytesWritten(),
            deflater.getTotalIn(), deflater.getTotalOut());
    }

    public static boolean endsWithTail(ByteBuffer buf)
    {
        if ((buf == null) || (buf.remaining() < TAIL_BYTES.length))
        {
            return false;
        }
        int limit = buf.limit();
        for (int i = TAIL_BYTES.length; i > 0; i--)
        {
            if (buf.get(limit - i) != TAIL_BYTES[TAIL_BYTES.length - i])
            {
                return false;
            }
        }
        return true;
    }

    public Deflater getDeflater()
    {
        if (deflaterHolder == null)
            deflaterHolder = getDeflaterPool().acquire();
        return deflaterHolder.get();
    }

    public Inflater getInflater()
    {
        if (inflaterHolder == null)
            inflaterHolder = getInflaterPool().acquire();
        return inflaterHolder.get();
    }

    public void releaseInflater()
    {
        if (inflaterHolder != null)
        {
            inflaterHolder.release();
            inflaterHolder = null;
        }
    }

    public void releaseDeflater()
    {
        if (deflaterHolder != null)
        {
            deflaterHolder.release();
            deflaterHolder = null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[requested=\"%s\", negotiated=\"%s\"]",
            getClass().getSimpleName(),
            configRequested.getParameterizedName(),
            configNegotiated.getParameterizedName());
    }

    @Override
    protected void nextIncomingFrame(Frame frame, Callback callback)
    {
        if (frame.isFin() && !incomingContextTakeover)
        {
            LOG.debug("Incoming Context Reset");
            releaseInflater();
        }
        super.nextIncomingFrame(frame, callback);
    }

    @Override
    protected void nextOutgoingFrame(Frame frame, Callback callback, boolean batch)
    {
        if (frame.isFin() && !outgoingContextTakeover)
        {
            LOG.debug("Outgoing Context Reset");
            releaseDeflater();
        }
        super.nextOutgoingFrame(frame, callback, batch);
    }

    @Override
    public void setNextDemand(DemandChain nextDemand)
    {
        incomingFlusher.setNextDemand(nextDemand);
    }

    @Override
    public void demand()
    {
        incomingFlusher.demand();
    }

    private class OutgoingFlusher extends TransformingFlusher
    {
        private boolean _first;
        private Frame _frame;
        private boolean _batch;

        @Override
        protected boolean onFrame(Frame frame, Callback callback, boolean batch)
        {
            if (frame.isControlFrame())
            {
                nextOutgoingFrame(frame, callback, batch);
                return true;
            }

            _first = true;
            _frame = frame;
            _batch = batch;

            // Provide the frames payload as input to the Deflater.
            getDeflater().setInput(frame.getPayload().slice());
            callback.succeeded();
            return false;
        }

        @Override
        protected boolean transform(Callback callback)
        {
            boolean finished = deflate(callback);
            _first = false;

            if (finished)
            {
                _frame = null;
                getDeflater().setInput(BufferUtil.EMPTY_BUFFER);
            }

            return finished;
        }

        private boolean deflate(Callback callback)
        {
            // Get a buffer for the deflated payload.
            long maxFrameSize = getConfiguration().getMaxFrameSize();
            int bufferSize = (maxFrameSize <= 0) ? deflateBufferSize : (int)Math.min(maxFrameSize, deflateBufferSize);
            RetainableByteBuffer buffer = getByteBufferPool().acquire(bufferSize, false);
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            callback = Callback.from(callback, buffer::release);
            buffer.clear();

            // Fill up the buffer with a max length of bufferSize;
            boolean finished = false;
            Deflater deflater = getDeflater();
            while (true)
            {
                int compressed = deflater.deflate(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(),
                    bufferSize - byteBuffer.position(), Deflater.SYNC_FLUSH);
                byteBuffer.limit(byteBuffer.limit() + compressed);
                if (LOG.isDebugEnabled())
                    LOG.debug("Compressed {} bytes {}", compressed, toDetail(deflater));

                if (byteBuffer.limit() == bufferSize)
                {
                    // We need to fragment.
                    if (!getConfiguration().isAutoFragment())
                        throw new MessageTooLargeException("Deflated payload exceeded the compress buffer size");
                    break;
                }

                if (compressed == 0)
                {
                    finished = true;
                    break;
                }
            }

            ByteBuffer payload = byteBuffer;
            if (payload.hasRemaining())
            {
                // Handle tail bytes generated by SYNC_FLUSH.
                if (finished && _frame.isFin() && endsWithTail(payload))
                {
                    payload.limit(payload.limit() - TAIL_BYTES.length);
                    if (LOG.isDebugEnabled())
                        LOG.debug("payload (TAIL_DROP_FIN_ONLY) = {}", BufferUtil.toDetailString(payload));
                }
            }
            else if (_frame.isFin())
            {
                // Special case: 7.2.3.6.  Generating an Empty Fragment Manually
                // https://tools.ietf.org/html/rfc7692#section-7.2.3.6
                payload = ByteBuffer.wrap(new byte[]{0x00});
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Compressed {}: payload:{}", _frame, payload.remaining());

            Frame chunk = new Frame(_first ? _frame.getOpCode() : OpCode.CONTINUATION);
            chunk.setRsv1(_first && _frame.getOpCode() != OpCode.CONTINUATION);
            chunk.setPayload(payload);
            chunk.setFin(_frame.isFin() && finished);

            nextOutgoingFrame(chunk, callback, _batch);
            return finished;
        }
    }

    private class IncomingFlusher extends DemandingFlusher
    {
        private boolean _tailBytes;
        private AtomicReference<RetainableByteBuffer> _payloadRef;

        public IncomingFlusher()
        {
            super(PerMessageDeflateExtension.this::nextIncomingFrame);
        }

        @Override
        protected boolean handle(Frame frame, Callback callback, boolean first)
        {
            if (first)
            {
                if (frame.isControlFrame())
                {
                    emitFrame(frame, callback);
                    return true;
                }

                // This extension requires the RSV1 bit set only in the first frame.
                // Subsequent continuation frames don't have RSV1 set, but are compressed.
                switch (frame.getOpCode())
                {
                    case OpCode.TEXT:
                    case OpCode.BINARY:
                        incomingCompressed = frame.isRsv1();
                        break;

                    case OpCode.CONTINUATION:
                        if (frame.isRsv1())
                            throw new ProtocolException("Invalid RSV1 set on permessage-deflate CONTINUATION frame");
                        break;

                    default:
                        break;
                }

                if (!incomingCompressed)
                {
                    emitFrame(frame, callback);
                    return true;
                }

                // Provide the frames payload as input to the Inflater.
                _tailBytes = false;
                getInflater().setInput(frame.getPayload().slice());
            }

            try
            {
                return inflate(frame, callback, first);
            }
            catch (DataFormatException e)
            {
                throw new BadPayloadException(e);
            }
        }

        private boolean inflate(Frame frame, Callback callback, boolean first) throws DataFormatException
        {
            // Get a buffer for the inflated payload.
            long maxFrameSize = getConfiguration().getMaxFrameSize();
            int bufferSize = (maxFrameSize <= 0) ? inflateBufferSize : (int)Math.min(maxFrameSize, inflateBufferSize);
            RetainableByteBuffer payload = getByteBufferPool().acquire(bufferSize, false);
            _payloadRef = new AtomicReference<>(payload);
            ByteBuffer byteBuffer = payload.getByteBuffer();
            payload.clear();

            // Fill up the ByteBuffer with a max length of bufferSize;
            Inflater inflater = getInflater();
            boolean complete = false;
            while (true)
            {
                int decompressed = inflater.inflate(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), bufferSize - byteBuffer.position());
                byteBuffer.limit(byteBuffer.limit() + decompressed);
                if (LOG.isDebugEnabled())
                    LOG.debug("Decompress: read {} {}", decompressed, toDetail(inflater));

                if (byteBuffer.limit() == bufferSize)
                {
                    // We need to fragment.
                    if (!getConfiguration().isAutoFragment())
                        throw new MessageTooLargeException("Inflated payload exceeded the decompress buffer size");
                    break;
                }

                if (decompressed == 0)
                {
                    if (!_tailBytes && frame.isFin())
                    {
                        inflater.setInput(TAIL_BYTES_BUF.slice());
                        _tailBytes = true;
                        continue;
                    }

                    inflater.setInput(BufferUtil.EMPTY_BUFFER);
                    complete = true;
                    break;
                }
            }

            Frame chunk = new Frame(first ? frame.getOpCode() : OpCode.CONTINUATION);
            chunk.setRsv1(false);
            chunk.setPayload(byteBuffer);
            chunk.setFin(frame.isFin() && complete);

            // If we are complete we return true, then DemandingFlusher.process() will null out the Frame and Callback.
            // The application may decide to hold onto the buffer and delay completing the callback, so we need to capture
            // references to these in the payloadCallback and not rely on state of the flusher which may have moved on.
            // This flusher could be failed while the application already has the payloadCallback, so we need protection against
            // the flusher failing and the application completing the callback, that's why we use the payload AtomicReference.
            boolean completeCallback = complete;
            AtomicReference<RetainableByteBuffer> payloadRef = _payloadRef;
            Callback payloadCallback = Callback.from(() ->
            {
                releasePayload(payloadRef);
                if (completeCallback)
                    callback.succeeded();
            }, t ->
            {
                releasePayload(payloadRef);
                if (completeCallback)
                    callback.failed(t);
                failFlusher(t);
            });

            emitFrame(chunk, payloadCallback);
            if (LOG.isDebugEnabled())
                LOG.debug("Decompress finished: {} {}", complete, chunk);
            return complete;
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            releasePayload(_payloadRef);
            super.onCompleteFailure(cause);
        }

        private void releasePayload(AtomicReference<RetainableByteBuffer> reference)
        {
            if (reference == null)
                return;
            RetainableByteBuffer buffer = reference.getAndSet(null);
            if (buffer != null)
                buffer.release();
        }
    }
}
