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

package org.eclipse.jetty.client.transport;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpChannel implements CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);

    private final AutoLock _lock = new AutoLock();
    private final HttpDestination _destination;
    private HttpExchange _exchange;

    protected HttpChannel(HttpDestination destination)
    {
        _destination = destination;
    }

    public void destroy()
    {
    }

    public HttpDestination getHttpDestination()
    {
        return _destination;
    }

    /**
     * <p>Associates the given {@code exchange} to this channel in order to be sent over the network.</p>
     * <p>If the association is successful, the exchange can be sent. Otherwise, the channel must be
     * disposed because whoever terminated the exchange did not do it - it did not have the channel yet.</p>
     *
     * @param exchange the exchange to associate
     * @return true if the association was successful, false otherwise
     */
    public boolean associate(HttpExchange exchange)
    {
        boolean result = false;
        boolean abort = true;
        try (AutoLock ignored = _lock.lock())
        {
            if (_exchange == null)
            {
                abort = false;
                result = exchange.associate(this);
                if (result)
                    _exchange = exchange;
            }
        }

        HttpRequest request = exchange.getRequest();
        if (abort)
        {
            request.abort(new UnsupportedOperationException("Pipelined requests not supported"));
        }
        else
        {
            request.setConnection(getConnection());
            if (LOG.isDebugEnabled())
                LOG.debug("associated {} {} to {}", result, exchange, this);
        }

        return result;
    }

    /**
     * <p>Disassociates the exchange from this channel.</p>
     *
     * @param exchange the current exchange that must be already completed
     * @return true if the exchange was disassociated, false otherwise
     */
    public boolean disassociate(HttpExchange exchange)
    {
        boolean result = false;
        try (AutoLock ignored = _lock.lock())
        {
            HttpExchange existing = _exchange;
            _exchange = null;
            if (existing == exchange)
            {
                existing.disassociate(this);
                result = true;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("disassociated {} {} from {}", result, exchange, this);
        return result;
    }

    /**
     * <p>Returns the {@code HttpExchange} currently associated
     * with this channel, possibly {@code null}.</p>
     * <p>The exchange may be completed and disassociated concurrently,
     * so callers must act atomically on the exchange.</p>
     *
     * @return the {@code HttpExchange} currently associated with this channel, possibly {@code null}.
     */
    public HttpExchange getHttpExchange()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _exchange;
        }
    }

    protected abstract Connection getConnection();

    @Override
    public long getExpireNanoTime()
    {
        HttpExchange exchange = getHttpExchange();
        return exchange != null ? exchange.getExpireNanoTime() : Long.MAX_VALUE;
    }

    protected abstract HttpSender getHttpSender();

    protected abstract HttpReceiver getHttpReceiver();

    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            send(exchange);
    }

    public abstract void send(HttpExchange exchange);

    public abstract void release();

    public void proceed(HttpExchange exchange, Runnable proceedAction, Throwable failure)
    {
        getHttpSender().proceed(exchange, proceedAction, failure);
    }

    public void abort(HttpExchange exchange, Throwable requestFailure, Throwable responseFailure, Promise<Boolean> promise)
    {
        Promise.Completable<Boolean> requestPromise = new Promise.Completable<>();
        if (requestFailure != null)
            getHttpSender().abort(exchange, requestFailure, requestPromise);
        else
            requestPromise.succeeded(false);

        Promise.Completable<Boolean> responsePromise = new Promise.Completable<>();
        if (responseFailure != null)
            abortResponse(exchange, responseFailure, responsePromise);
        else
            responsePromise.succeeded(false);

        promise.completeWith(requestPromise.thenCombine(responsePromise, (requestAborted, responseAborted) -> requestAborted || responseAborted));
    }

    public void abortResponse(HttpExchange exchange, Throwable failure, Promise<Boolean> promise)
    {
        getHttpReceiver().abort(exchange, failure, promise);
    }

    public Result exchangeTerminating(HttpExchange exchange, Result result)
    {
        return result;
    }

    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        disassociate(exchange);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(exchange=%s)", getClass().getSimpleName(), hashCode(), getHttpExchange());
    }
}
