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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee9.nested.QuietServletException;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * This tests the correct functioning of the AsyncContext
 * <p/>
 * tests for #371649 and #371635
 */
public class AsyncContextTest
{
    private Server _server;
    private ServletContextHandler _contextHandler;
    private LocalConnector _connector;

    private void startServer(Consumer<ServletContextHandler> configServletContext) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.setIdleTimeout(5000);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        _server.addConnector(_connector);

        _contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        _contextHandler.setContextPath("/ctx");

        configServletContext.accept(_contextHandler);

        _server.setHandler(_contextHandler);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testSimpleAsyncContext() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/servletPath");
        });

        String request = """
            GET /ctx/servletPath HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat(responseBody, containsString("doGet:getServletPath:/servletPath"));
        assertThat(responseBody, containsString("doGet:async:getServletPath:/servletPath"));
        assertThat(responseBody, containsString("async:run:attr:servletPath:/servletPath"));
    }

    @Test
    public void testStartThrow() throws Exception
    {
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        startServer((config) ->
        {
            _contextHandler.addServlet(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    AsyncContext async = request.startAsync(request, response);
                    asyncContextRef.set(async);
                    response.getOutputStream().write("wrote text just fine".getBytes(StandardCharsets.UTF_8));
                    throw new QuietServletException(new IOException("Test"));
                }
            }), "/startthrow/*");
            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(IOException.class.getName(), "/error/IOE");
        });

        String request =
            """
                GET /ctx/startthrow HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """;

        try (LocalConnector.LocalEndPoint localEndPoint = _connector.connect())
        {
            localEndPoint.addInputAndExecute(request);
            assertThat(localEndPoint.getResponse(false, 1, TimeUnit.SECONDS), nullValue());

            await().atMost(5, TimeUnit.SECONDS).until(asyncContextRef::get, not(nullValue())).complete();

            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 10, TimeUnit.SECONDS));
            assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

            String responseBody = response.getContent();
            assertThat(responseBody, is("wrote text just fine"));
        }
    }

    @Test
    public void testStartDispatchThrow() throws Exception
    {
        startServer((config) ->
        {
            _contextHandler.addServlet(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException
                {
                    AsyncContext async = request.startAsync(request, response);
                    async.dispatch("/dispatch-landing/");
                    throw new QuietServletException(new IOException("Test"));
                }
            }), "/startthrow/*");
            _contextHandler.addServlet(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
                {
                    response.getOutputStream().write("Landed just fine".getBytes(StandardCharsets.UTF_8));
                }
            }), "/dispatch-landing/*");

            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(IOException.class.getName(), "/error/IOE");
        });

        String request =
            """
                GET /ctx/startthrow HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        // OK b/c exception was thrown after AsyncContext.dispatch() was called
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat(responseBody, is("Landed just fine"));
    }

    @Test
    public void testStartCompleteThrow() throws Exception
    {
        startServer((config) ->
        {
            _contextHandler.addServlet(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    AsyncContext async = request.startAsync(request, response);
                    response.getOutputStream().write("completeBeforeThrow".getBytes(StandardCharsets.UTF_8));
                    async.complete();
                    throw new QuietServletException(new IOException("Test"));
                }
            }), "/startthrow/*");
            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(IOException.class.getName(), "/error/IOE");
        });

        String request = """
            GET /ctx/startthrow HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        // OK b/c exception was thrown after AsyncContext.complete() was called
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();
        assertThat(responseBody, containsString("completeBeforeThrow"));
    }

    @Test
    public void testStartFlushCompleteThrow() throws Exception
    {
        startServer((config) ->
        {
            _contextHandler.addServlet(new ServletHolder(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    AsyncContext async = request.startAsync(request, response);
                    response.getOutputStream().write("completeBeforeThrow".getBytes());
                    response.flushBuffer();
                    async.complete();
                    throw new QuietServletException(new IOException("Test"));
                }
            }), "/startthrow/*");
            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(IOException.class.getName(), "/error/IOE");
        });

        String request = """
            GET /ctx/startthrow HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("error servlet", responseBody, containsString("completeBeforeThrow"));
    }

    @Test
    public void testDispatchAsyncContext() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/servletPath");
            _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/servletPath2");
        });

        String request = """
            GET /ctx/servletPath?dispatch=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();
        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/servletPath"));
        assertThat("http servlet mapping matchValue is correct", responseBody, containsString("async:run:attr:mapping:matchValue:servletPath"));
        assertThat("http servlet mapping pattern is correct", responseBody, containsString("async:run:attr:mapping:pattern:/servletPath"));
        assertThat("http servlet mapping servletName is correct", responseBody, containsString("async:run:attr:mapping:servletName:"));
        assertThat("http servlet mapping mappingMatch is correct", responseBody, containsString("async:run:attr:mapping:mappingMatch:EXACT"));
    }

    @Test
    public void testDispatchAsyncContextEncodedUrl() throws Exception
    {
        startServer((context) ->
        {
            ServletHolder encodedTestHolder = new ServletHolder(new TestServlet());
            encodedTestHolder.setInitParameter("dispatchPath", "/test2/something%25else");
            _contextHandler.addServlet(encodedTestHolder, "/encoded/*");
            _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/test2/*");
        });

        String request = """
            GET /ctx/encoded/hello%20there?dispatch=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        // initial values
        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/test2"));
        assertThat("request uri has correct encoding", responseBody, containsString("doGet:getRequestURI:/ctx/test2/something%25else"));
        assertThat("request url has correct encoding", responseBody, containsString("doGet:getRequestURL:http://localhost/ctx/test2/something%25else"));
        assertThat("path info has correct encoding", responseBody, containsString("doGet:getPathInfo:/something%else"));

        // async values
        assertThat("async servlet gets right path", responseBody, containsString("doGet:async:getServletPath:/test2"));
        assertThat("async request uri has correct encoding", responseBody, containsString("doGet:async:getRequestURI:/ctx/test2/something%25else"));
        assertThat("async request url has correct encoding", responseBody, containsString("doGet:async:getRequestURL:http://localhost/ctx/test2/something%25else"));
        assertThat("async path info has correct encoding", responseBody, containsString("doGet:async:getPathInfo:/something%else"));

        // async run attributes
        assertThat("async run attr servlet path is original", responseBody, containsString("async:run:attr:servletPath:/encoded"));
        assertThat("async run attr path info has correct encoding", responseBody, containsString("async:run:attr:pathInfo:/hello there"));
        assertThat("async run attr query string", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("async run context path", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("async run request uri has correct encoding", responseBody, containsString("async:run:attr:requestURI:/ctx/encoded/hello%20there"));
        assertThat("http servlet mapping matchValue is correct", responseBody, containsString("async:run:attr:mapping:matchValue:encoded"));
        assertThat("http servlet mapping pattern is correct", responseBody, containsString("async:run:attr:mapping:pattern:/encoded/*"));
        assertThat("http servlet mapping servletName is correct", responseBody, containsString("async:run:attr:mapping:servletName:"));
        assertThat("http servlet mapping mappingMatch is correct", responseBody, containsString("async:run:attr:mapping:mappingMatch:PATH"));
    }

    @Test
    public void testDispatchAsyncContextSelfEncodedUrl() throws Exception
    {
        startServer((context) ->
        {
            HttpServlet selfDispatchingServlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, final HttpServletResponse response) throws IOException
                {
                    DispatcherType dispatcherType = request.getDispatcherType();

                    ServletOutputStream out = response.getOutputStream();
                    out.print("doGet.%s.requestURI:%s\n".formatted(dispatcherType.name(), request.getRequestURI()));
                    out.print("doGet.%s.requestURL:%s\n".formatted(dispatcherType.name(), request.getRequestURL()));

                    if (dispatcherType == DispatcherType.ASYNC)
                    {
                        response.getOutputStream().print("Dispatched back to SelfDispatchingServlet\n");
                    }
                    else
                    {
                        final AsyncContext asyncContext = request.startAsync(request, response);
                        new Thread(asyncContext::dispatch).start();
                    }
                }
            };

            ServletHolder holder = new ServletHolder(selfDispatchingServlet);
            _contextHandler.addServlet(holder, "/self/*");
        });

        String request = """
            GET /ctx/self/hello%20there?dispatch=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet request uri initial", responseBody, containsString("doGet.REQUEST.requestURI:/ctx/self/hello%20there\n"));
        assertThat("servlet request uri initial", responseBody, containsString("doGet.REQUEST.requestURL:http://localhost/ctx/self/hello%20there\n"));
        assertThat("servlet request uri async", responseBody, containsString("doGet.ASYNC.requestURI:/ctx/self/hello%20there\n"));
        assertThat("servlet request uri async", responseBody, containsString("doGet.ASYNC.requestURL:http://localhost/ctx/self/hello%20there\n"));
    }

    @Test
    public void testDispatchAsyncContextEncodedPathAndQueryString() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/path with spaces/servletPath");
            _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/servletPath2");
        });

        String request = """
            GET /ctx/path%20with%20spaces/servletPath?dispatch=true&queryStringWithEncoding=space%20space HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/path with spaces/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true&queryStringWithEncoding=space%20space"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/path%20with%20spaces/servletPath"));
        assertThat("http servlet mapping matchValue is correct", responseBody, containsString("async:run:attr:mapping:matchValue:path with spaces/servletPath"));
        assertThat("http servlet mapping pattern is correct", responseBody, containsString("async:run:attr:mapping:pattern:/path with spaces/servletPath"));
        assertThat("http servlet mapping servletName is correct", responseBody, containsString("async:run:attr:mapping:servletName:"));
        assertThat("http servlet mapping mappingMatch is correct", responseBody, containsString("async:run:attr:mapping:mappingMatch:EXACT"));
    }

    @Test
    public void testSimpleWithContextAsyncContext() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/servletPath");
        });

        String request = """
            GET /ctx/servletPath HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath"));
        assertThat("async context gets right path in async", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
    }

    @Test
    public void testDispatchWithContextAsyncContext() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new TestServlet()), "/servletPath");
            _contextHandler.addServlet(new ServletHolder(new TestServlet2()), "/servletPath2");
        });

        String request = """
            GET /ctx/servletPath?dispatch=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("servlet gets right path", responseBody, containsString("doGet:getServletPath:/servletPath2"));
        assertThat("async context gets right path in get", responseBody, containsString("doGet:async:getServletPath:/servletPath2"));
        assertThat("servlet path attr is original", responseBody, containsString("async:run:attr:servletPath:/servletPath"));
        assertThat("path info attr is correct", responseBody, containsString("async:run:attr:pathInfo:null"));
        assertThat("query string attr is correct", responseBody, containsString("async:run:attr:queryString:dispatch=true"));
        assertThat("context path attr is correct", responseBody, containsString("async:run:attr:contextPath:/ctx"));
        assertThat("request uri attr is correct", responseBody, containsString("async:run:attr:requestURI:/ctx/servletPath"));
        assertThat("http servlet mapping matchValue is correct", responseBody, containsString("async:run:attr:mapping:matchValue:servletPath"));
        assertThat("http servlet mapping pattern is correct", responseBody, containsString("async:run:attr:mapping:pattern:/servletPath"));
        assertThat("http servlet mapping servletName is correct", responseBody, containsString("async:run:attr:mapping:servletName:"));
        assertThat("http servlet mapping mappingMatch is correct", responseBody, containsString("async:run:attr:mapping:mappingMatch:EXACT"));
    }

    @Test
    public void testDispatch() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new ForwardingServlet()), "/forward");
            _contextHandler.addServlet(new ServletHolder(new AsyncDispatchingServlet()), "/dispatchingServlet");
        });

        String request =
            """
            GET /ctx/forward HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;

        String responseString = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(responseString);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();
        assertThat("!ForwardingServlet", responseBody, containsString("Dispatched back to ForwardingServlet"));
    }

    @Test
    public void testDispatchRequestResponse() throws Exception
    {
        startServer((context) ->
        {
            _contextHandler.addServlet(new ServletHolder(new ForwardingServlet()), "/forward");
            _contextHandler.addServlet(new ServletHolder(new AsyncDispatchingServlet()), "/dispatchingServlet");
        });

        String request = """
            GET /ctx/forward?dispatchRequestResponse=true HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;

        String responseString = _connector.getResponse(request);

        HttpTester.Response response = HttpTester.parseResponse(responseString);
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_OK));

        String responseBody = response.getContent();

        assertThat("!AsyncDispatchingServlet", responseBody, containsString("Dispatched back to AsyncDispatchingServlet"));
    }

    private static class ForwardingServlet extends HttpServlet
    {
        @Override
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to ForwardingServlet");
            }
            else
            {
                request.getRequestDispatcher("/dispatchingServlet").forward(request, response);
            }
        }
    }

    private static class AsyncDispatchingServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, final HttpServletResponse response) throws IOException
        {
            Request request = (Request)req;
            if (request.getDispatcherType() == DispatcherType.ASYNC)
            {
                response.getOutputStream().print("Dispatched back to AsyncDispatchingServlet");
            }
            else
            {
                boolean wrapped = false;
                final AsyncContext asyncContext;
                if (request.getParameter("dispatchRequestResponse") != null)
                {
                    wrapped = true;
                    asyncContext = request.startAsync(request, new Wrapper(response));
                }
                else
                {
                    asyncContext = request.startAsync();
                }

                new Thread(new DispatchingRunnable(asyncContext, wrapped)).start();
            }
        }
    }

    @Test
    public void testExpire() throws Exception
    {
        startServer((context) ->
        {
            HttpServlet expireServlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                {
                    if (request.getDispatcherType() == DispatcherType.REQUEST)
                    {
                        AsyncContext asyncContext = request.startAsync();
                        asyncContext.setTimeout(100);
                    }
                }
            };

            _contextHandler.addServlet(new ServletHolder(expireServlet), "/expire/*");
            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");

            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(500, "/error/500");
        });

        String request = """
            GET /ctx/expire HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat("error servlet", responseBody, containsString("ERROR: /error"));
    }

    @Test
    public void testBadExpire() throws Exception
    {
        startServer((context) ->
        {
            HttpServlet badExpireServlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    if (request.getDispatcherType() == DispatcherType.REQUEST)
                    {
                        AsyncContext asyncContext = request.startAsync();
                        asyncContext.addListener(new AsyncListener()
                        {
                            @Override
                            public void onTimeout(AsyncEvent event)
                            {
                                throw new RuntimeException("BAD EXPIRE");
                            }

                            @Override
                            public void onStartAsync(AsyncEvent event)
                            {
                            }

                            @Override
                            public void onError(AsyncEvent event)
                            {
                            }

                            @Override
                            public void onComplete(AsyncEvent event)
                            {
                            }
                        });
                        asyncContext.setTimeout(100);
                    }
                }
            };

            _contextHandler.addServlet(new ServletHolder(badExpireServlet), "/badexpire/*");
            _contextHandler.addServlet(new ServletHolder(new ErrorServlet()), "/error/*");

            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.setUnwrapServletException(false);
            _contextHandler.setErrorHandler(errorHandler);
            errorHandler.addErrorPage(500, "/error/500");
        });

        String request = """
            GET /ctx/badexpire HTTP/1.1\r
            Host: localhost\r
            Content-Type: application/x-www-form-urlencoded\r
            Connection: close\r
            \r
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertThat("Response.status", response.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

        String responseBody = response.getContent();

        assertThat("error servlet", responseBody, containsString("ERROR: /error"));
        assertThat("error servlet", responseBody, containsString("PathInfo= /500"));
        assertThat("error servlet", responseBody, not(containsString("EXCEPTION: ")));
    }

    private static class DispatchingRunnable implements Runnable
    {
        private final AsyncContext asyncContext;
        private final boolean wrapped;

        public DispatchingRunnable(AsyncContext asyncContext, boolean wrapped)
        {
            this.asyncContext = asyncContext;
            this.wrapped = wrapped;
        }

        @Override
        public void run()
        {
            if (wrapped)
                assertInstanceOf(Wrapper.class, asyncContext.getResponse());
            asyncContext.dispatch();
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    private static class ErrorServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.getOutputStream().print("ERROR: " + request.getServletPath() + "\n");
            response.getOutputStream().print("PathInfo= " + request.getPathInfo() + "\n");
            if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) != null)
                response.getOutputStream().print("EXCEPTION: " + request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) + "\n");
        }
    }

    private static class TestServlet extends HttpServlet
    {
        private String dispatchPath = "/servletPath2";

        @Override
        public void init()
        {
            String dispatchTo = getServletConfig().getInitParameter("dispatchPath");
            if (StringUtil.isNotBlank(dispatchTo))
            {
                this.dispatchPath = dispatchTo;
            }
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (request.getParameter("dispatch") != null)
            {
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.dispatch(dispatchPath);
            }
            else
            {
                response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");
                response.getOutputStream().print("doGet:getRequestURI:" + request.getRequestURI() + "\n");
                response.getOutputStream().print("doGet:getRequestURL:" + request.getRequestURL() + "\n");
                response.getOutputStream().print("doGet:getPathInfo:" + request.getPathInfo() + "\n");
                AsyncContext asyncContext = request.startAsync(request, response);
                HttpServletRequest asyncRequest = (HttpServletRequest)asyncContext.getRequest();
                response.getOutputStream().print("doGet:async:getServletPath:" + asyncRequest.getServletPath() + "\n");
                response.getOutputStream().print("doGet:async:getRequestURI:" + asyncRequest.getRequestURI() + "\n");
                response.getOutputStream().print("doGet:async:getRequestURL:" + asyncRequest.getRequestURL() + "\n");
                response.getOutputStream().print("doGet:async:getPathInfo:" + asyncRequest.getPathInfo() + "\n");
                asyncContext.start(new AsyncRunnable(asyncContext));
            }
        }
    }

    private static class TestServlet2 extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.getOutputStream().print("doGet:getServletPath:" + request.getServletPath() + "\n");
            response.getOutputStream().print("doGet:getRequestURI:" + request.getRequestURI() + "\n");
            response.getOutputStream().print("doGet:getRequestURL:" + request.getRequestURL() + "\n");
            response.getOutputStream().print("doGet:getPathInfo:" + request.getPathInfo() + "\n");
            AsyncContext asyncContext = request.startAsync(request, response);
            HttpServletRequest asyncRequest = (HttpServletRequest)asyncContext.getRequest();
            response.getOutputStream().print("doGet:async:getServletPath:" + asyncRequest.getServletPath() + "\n");
            response.getOutputStream().print("doGet:async:getRequestURI:" + asyncRequest.getRequestURI() + "\n");
            response.getOutputStream().print("doGet:async:getRequestURL:" + asyncRequest.getRequestURL() + "\n");
            response.getOutputStream().print("doGet:async:getPathInfo:" + asyncRequest.getPathInfo() + "\n");
            asyncContext.start(new AsyncRunnable(asyncContext));
        }
    }

    private static class AsyncRunnable implements Runnable
    {
        private static final Logger LOG = LoggerFactory.getLogger(AsyncRunnable.class);
        private final AsyncContext _context;

        public AsyncRunnable(AsyncContext context)
        {
            _context = context;
        }

        @Override
        public void run()
        {
            HttpServletRequest req = (HttpServletRequest)_context.getRequest();

            try
            {
                _context.getResponse().getOutputStream().print("async:run:attr:servletPath:" + req.getAttribute(AsyncContext.ASYNC_SERVLET_PATH) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:pathInfo:" + req.getAttribute(AsyncContext.ASYNC_PATH_INFO) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:queryString:" + req.getAttribute(AsyncContext.ASYNC_QUERY_STRING) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:contextPath:" + req.getAttribute(AsyncContext.ASYNC_CONTEXT_PATH) + "\n");
                _context.getResponse().getOutputStream().print("async:run:attr:requestURI:" + req.getAttribute(AsyncContext.ASYNC_REQUEST_URI) + "\n");
                HttpServletMapping mapping = (HttpServletMapping)req.getAttribute(AsyncContext.ASYNC_MAPPING);
                if (mapping != null)
                {
                    _context.getResponse().getOutputStream().print("async:run:attr:mapping:matchValue:" + mapping.getMatchValue() + "\n");
                    _context.getResponse().getOutputStream().print("async:run:attr:mapping:pattern:" + mapping.getPattern() + "\n");
                    _context.getResponse().getOutputStream().print("async:run:attr:mapping:servletName:" + mapping.getServletName() + "\n");
                    _context.getResponse().getOutputStream().print("async:run:attr:mapping:mappingMatch:" + mapping.getMappingMatch() + "\n");
                }
            }
            catch (IOException e)
            {
                LOG.warn("Unexpected", e);
            }
            _context.complete();
        }
    }

    private static class Wrapper extends HttpServletResponseWrapper
    {
        public Wrapper(HttpServletResponse response)
        {
            super(response);
        }
    }
}
