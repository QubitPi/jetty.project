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

package org.eclipse.jetty.http2.server;

import java.io.EOFException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerConnection;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerSession;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NegotiatingServerConnection.CipherDiscriminator;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2ServerConnectionFactory extends AbstractHTTP2ServerConnectionFactory implements CipherDiscriminator
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2ServerConnectionFactory.class);

    public HTTP2ServerConnectionFactory()
    {
        this(new HttpConfiguration());
    }

    public HTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        super(httpConfiguration);
    }

    public HTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration, @Name("protocols") String... protocols)
    {
        super(httpConfiguration, protocols);
    }

    @Override
    protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
    {
        return new HTTPServerSessionListener(endPoint);
    }

    @Override
    public boolean isAcceptable(String protocol, String tlsProtocol, String tlsCipher)
    {
        // Implement 9.2.2 for draft 14
        boolean acceptable = "h2-14".equals(protocol) || !(HTTP2Cipher.isBlackListProtocol(tlsProtocol) && HTTP2Cipher.isBlackListCipher(tlsCipher));
        if (LOG.isDebugEnabled())
            LOG.debug("proto={} tls={} cipher={} 9.2.2-acceptable={}", protocol, tlsProtocol, tlsCipher, acceptable);
        return acceptable;
    }

    protected class HTTPServerSessionListener implements HTTP2ServerSession.Listener, Stream.Listener
    {
        private final EndPoint endPoint;

        public HTTPServerSessionListener(EndPoint endPoint)
        {
            this.endPoint = endPoint;
        }

        private HTTP2ServerConnection getConnection()
        {
            return (HTTP2ServerConnection)endPoint.getConnection();
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            return newSettings();
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            getConnection().onNewStream((HTTP2Stream)stream, frame);
            // Do not demand for DATA frames.
            // This allows CONNECT requests with pseudo header :protocol
            // (e.g. WebSocket over HTTP/2) to buffer DATA frames
            // until they upgrade and are ready to process them.
            return this;
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            long idleTimeout = getConnection().getEndPoint().getIdleTimeout();
            return getConnection().onSessionTimeout(new TimeoutException("Session idle timeout " + idleTimeout + " ms"));
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame, Callback callback)
        {
            String reason = frame.tryConvertPayload();
            if (!StringUtil.isEmpty(reason))
                reason = " (" + reason + ")";
            EofException failure = new EofException(String.format("Close %s/%s", ErrorCode.toString(frame.getError(), null), reason));
            onFailure(session, failure, callback);
        }

        @Override
        public void onFailure(Session session, Throwable failure, Callback callback)
        {
            getConnection().onSessionFailure(failure, callback);
        }

        @Override
        public void onStreamFailure(Stream stream, Throwable failure, Callback callback)
        {
            onFailure(stream, failure, callback);
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            if (frame.isEndStream())
                getConnection().onTrailers(stream, frame);
            else
                close(stream, "invalid_trailers");
        }

        @Override
        public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            // Servers do not receive pushes.
            close(stream, "push_promise");
            return null;
        }

        @Override
        public void onDataAvailable(Stream stream)
        {
            getConnection().onDataAvailable(stream);
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            EOFException failure = new EOFException("Reset " + ErrorCode.toString(frame.getError(), null));
            onFailure(stream, failure, callback);
        }

        @Override
        public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
        {
            if (!(failure instanceof QuietException))
                failure = new EofException(failure);
            onFailure(stream, failure, callback);
        }

        private void onFailure(Stream stream, Throwable failure, Callback callback)
        {
            getConnection().onStreamFailure(stream, failure, callback);
        }

        @Override
        public void onIdleTimeout(Stream stream, TimeoutException x, Promise<Boolean> promise)
        {
            getConnection().onStreamTimeout(stream, x, promise);
        }

        private void close(Stream stream, String reason)
        {
            stream.getSession().close(ErrorCode.PROTOCOL_ERROR.code, reason, Callback.NOOP);
        }
    }
}
