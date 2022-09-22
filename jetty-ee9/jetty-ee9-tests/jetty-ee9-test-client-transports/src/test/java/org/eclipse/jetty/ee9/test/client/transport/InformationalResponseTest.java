//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: these tests fail with "last already written" since in core Jetty
//  we don't handle well writing 2 HTTP responses one after the other.
@Disabled
public class InformationalResponseTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void test102Processing(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.sendError(HttpStatus.PROCESSING_102);
                response.sendError(HttpStatus.PROCESSING_102);
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        setStreamIdleTimeout(idleTimeout);

        CountDownLatch processingLatch = new CountDownLatch(1);
        client.getProtocolHandlers().put(new ProtocolHandler()
        {
            @Override
            public String getName()
            {
                return "Processing";
            }

            @Override
            public boolean accept(org.eclipse.jetty.client.api.Request request, Response response)
            {
                return response.getStatus() == HttpStatus.PROCESSING_102;
            }

            @Override
            public Response.Listener getResponseListener()
            {
                return new Response.Listener()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        processingLatch.countDown();
                        var request = response.getRequest();
                        HttpConversation conversation = ((HttpRequest)request).getConversation();
                        // Reset the conversation listeners, since we are going to receive another response code
                        conversation.updateResponseListeners(null);

                        HttpExchange exchange = conversation.getExchanges().peekLast();
                        if (exchange != null && response.getStatus() == HttpStatus.PROCESSING_102)
                        {
                            // All good, continue.
                            exchange.resetResponse();
                        }
                        else
                        {
                            response.abort(new IllegalStateException("should not have accepted"));
                        }
                    }
                };
            }
        });

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                response.set(result.getResponse());
                completeLatch.countDown();
            }
        };
        client.newRequest(newURI(transport))
            .method("GET")
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.PROCESSING))
            .timeout(10, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(processingLatch.await(10, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(10, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void test103EarlyHint(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setHeader("Hint", "one");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.setHeader("Hint", "two");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.setHeader("Hint", "three");
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        setStreamIdleTimeout(idleTimeout);

        List<String> hints = new CopyOnWriteArrayList<>();
        client.getProtocolHandlers().put(new ProtocolHandler()
        {
            @Override
            public String getName()
            {
                return "EarlyHint";
            }

            @Override
            public boolean accept(org.eclipse.jetty.client.api.Request request, Response response)
            {
                return response.getStatus() == HttpStatus.EARLY_HINT_103;
            }

            @Override
            public Response.Listener getResponseListener()
            {
                return new Response.Listener()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        var request = response.getRequest();
                        HttpConversation conversation = ((HttpRequest)request).getConversation();
                        // Reset the conversation listeners, since we are going to receive another response code
                        conversation.updateResponseListeners(null);

                        HttpExchange exchange = conversation.getExchanges().peekLast();
                        if (exchange != null && response.getStatus() == HttpStatus.EARLY_HINT_103)
                        {
                            // All good, continue.
                            hints.add(response.getHeaders().get("Hint"));
                            exchange.resetResponse();
                        }
                        else
                        {
                            response.abort(new IllegalStateException("should not have accepted"));
                        }
                    }
                };
            }
        });

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                hints.add(result.getResponse().getHeaders().get("Hint"));
                response.set(result.getResponse());
                complete.countDown();
            }
        };
        client.newRequest(newURI(transport))
            .method("GET")
            .timeout(5, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
        assertThat(hints, contains("one", "two", "three"));
    }
}