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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CustomRequestLogTest
{
    private final BlockingQueue<String> _logs = new BlockingArrayQueue<>();
    private Server _server;
    private HttpConfiguration _httpConfig;
    private ServerConnector _serverConnector;
    private CustomRequestLog _log;

    private void start(String formatString) throws Exception
    {
        start(formatString, (log) -> {});
    }

    private void start(String formatString, Consumer<CustomRequestLog> configCustomRequestLog) throws Exception
    {
        start(formatString, new SimpleHandler(), configCustomRequestLog);
    }

    private void start(String formatString, Handler handler) throws Exception
    {
        start(formatString, handler, (log) -> {});
    }

    private void start(String formatString, Handler handler, Consumer<CustomRequestLog> configCustomRequestLog) throws Exception
    {
        _server = new Server();
        _httpConfig = new HttpConfiguration();
        _serverConnector = new ServerConnector(_server, 1, 1, new HttpConnectionFactory(_httpConfig));
        _server.addConnector(_serverConnector);
        TestRequestLogWriter writer = new TestRequestLogWriter();
        _log = new CustomRequestLog(writer, formatString);
        if (configCustomRequestLog != null)
            configCustomRequestLog.accept(_log);
        _server.setRequestLog(_log);
        _server.setHandler(handler);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        LifeCycle.stop(_server);
    }

    private HttpTester.Response getResponse(String request) throws IOException
    {
        return getResponses(request, 1).get(0);
    }

    private List<HttpTester.Response> getResponses(String request, int count) throws IOException
    {
        try (Socket socket = new Socket("localhost", _serverConnector.getLocalPort()))
        {
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);

            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            List<HttpTester.Response> result = new ArrayList<>();
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            for (int i = 0; i < count; ++i)
            {
                HttpTester.Response response = HttpTester.parseResponse(input);
                if (response != null)
                    result.add(response);
            }
            return result;
        }
    }

    @Test
    public void testRequestFilter() throws Exception
    {
        start("RequestPath: %U");
        AtomicReference<Boolean> logRequest = new AtomicReference<>();
        _log.setFilter((request, response) -> logRequest.get());

        logRequest.set(true);
        HttpTester.Response response = getResponse("GET /path HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestPath: /path"));

        logRequest.set(false);
        response = getResponse("GET /path HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        log = _logs.poll(1, TimeUnit.SECONDS);
        assertNull(log);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
        /foo/a/,true
        /zed/b/,false
        /zef/c/,true
        /zee/d/,false
        """)
    public void testIgnorePaths(String testPath, boolean existsInLog) throws Exception
    {
        start("RequestPath: %U",
            customRequestLog ->
            {
                customRequestLog.setIgnorePaths(new String[]{"/zed/*", "/zee/*"});
            });

        HttpTester.Response response = getResponse("GET @PATH@ HTTP/1.0\n\n".replace("@PATH@", testPath));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        if (existsInLog)
        {
            String log = _logs.poll(5, TimeUnit.SECONDS);
            assertThat(log, is("RequestPath: " + testPath));
        }
        else
        {
            String log = _logs.poll(1, TimeUnit.SECONDS);
            assertNull(log);
        }
    }

    @Test
    public void testModifier() throws Exception
    {
        start("%s: %!404,301{Referer}i", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String status = request.getHeaders().get("Status");
                response.setStatus(Integer.parseInt(status));
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("""
            GET /path HTTP/1.0
            Status: 404
            Referer: testReferer

            """);
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("404: -"));

        response = getResponse("""
            GET /path HTTP/1.0
            Status: 301
            Referer: testReferer

            """);
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("301: -"));

        response = getResponse("""
            GET /success HTTP/1.0
            Status: 200
            Referer: testReferer

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("200: testReferer"));
    }

    @Test
    public void testDoublePercent() throws Exception
    {
        start("%%%%%%a");

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("%%%a"));
    }

    @Test
    public void testLogAddress() throws Exception
    {
        start("" +
              "%{local}a|%{local}p|" +
              "%{remote}a|%{remote}p|" +
              "%{server}a|%{server}p|" +
              "%{client}a|%{client}p");
        _httpConfig.addCustomizer(new ForwardedRequestCustomizer());

        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements())
        {
            NetworkInterface n = e.nextElement();
            if (n.isLoopback())
            {
                Enumeration<InetAddress> ee = n.getInetAddresses();
                while (ee.hasMoreElements())
                {
                    InetAddress i = ee.nextElement();
                    try (Socket client = new Socket(i.getHostAddress(), _serverConnector.getLocalPort()))
                    {
                        OutputStream output = client.getOutputStream();
                        String request = """
                            GET / HTTP/1.1
                            Host: webtide.com:1234
                            Forwarded: For=10.1.2.3:1337

                            """;
                        output.write(request.getBytes(StandardCharsets.UTF_8));
                        output.flush();

                        String[] log = Objects.requireNonNull(_logs.poll(5, TimeUnit.SECONDS)).split("\\|");
                        assertThat(log.length, is(8));

                        String localAddr = log[0];
                        String localPort = log[1];
                        String remoteAddr = log[2];
                        String remotePort = log[3];
                        String serverAddr = log[4];
                        String serverPort = log[5];
                        String clientAddr = log[6];
                        String clientPort = log[7];

                        assertThat(InetAddress.getByName(localAddr), is(i));
                        assertThat(localPort, not(serverPort));
                        assertThat(InetAddress.getByName(remoteAddr), is(client.getInetAddress()));
                        assertThat(remotePort, not(clientPort));
                        assertThat(serverAddr, is("webtide.com"));
                        assertThat(serverPort, is("1234"));
                        assertThat(clientAddr, is("10.1.2.3"));
                        assertThat(clientPort, is("1337"));
                    }
                }
            }
        }
    }

    @Test
    public void testLogBytesSent() throws Exception
    {
        String content = "hello world";
        start("BytesSent: %O", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesSent: " + content.length()));
    }

    @Test
    public void testLogBytesReceived() throws Exception
    {
        String content = "hello world";
        start("BytesReceived: %I", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            Content-Length: %d
                                
            %s""".formatted(content.length(), content));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesReceived: " + content.length()));
    }

    @Test
    public void testLogBytesTransferred() throws Exception
    {
        String content = "hello world";
        start("BytesTransferred: %S", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String content = Content.Source.asString(request);
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            Content-Length: %d

            %s""".formatted(content.length(), content));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesTransferred: " + (2 * content.length())));
    }

    @Test
    public void testLogRequestCookie() throws Exception
    {
        start("RequestCookies: %{cookieName}C, %{cookie2}C, %{cookie3}C");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            Cookie: cookieName=cookieValue; cookie2=value2

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieValue, value2, -"));
    }

    @Test
    public void testLogRequestCookies() throws Exception
    {
        start("RequestCookies: %C");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            Cookie: cookieName=cookieValue; cookie2=value2

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieName=cookieValue;cookie2=value2"));
    }

    @Test
    public void testLogEnvironmentVar() throws Exception
    {
        start("EnvironmentVar: %{JAVA_HOME}e");

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        String envVar = System.getenv("JAVA_HOME");
        assertThat(log, is("EnvironmentVar: " + ((envVar == null) ? "-" : envVar)));
    }

    @Test
    public void testLogRequestProtocol() throws Exception
    {
        start("%H");

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("HTTP/1.0"));
    }

    @Test
    public void testLogRequestHeader() throws Exception
    {
        start("RequestHeader: %{Header1}i, %{Header2}i, %{Header3}i");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            Header1: value1
            Header2: value2

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestHeader: value1, value2, -"));
    }

    @Test
    public void testLogKeepAliveRequests() throws Exception
    {
        start("KeepAliveRequests: %k");

        getResponses("""
            GET /a HTTP/1.0
            Connection: keep-alive

            GET /a HTTP/1.1
            Host: localhost
                        
            GET /a HTTP/1.0
                        
            """, 3);

        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 1"));
        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 2"));
        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 3"));
    }

    @Test
    public void testLogRequestMethod() throws Exception
    {
        start("RequestMethod: %m");

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestMethod: GET"));
    }

    @Test
    public void testLogResponseHeader() throws Exception
    {
        start("ResponseHeader: %{Header1}o, %{Header2}o, %{Header3}o", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().add("Header1", "value1");
                response.getHeaders().add("Header2", "value2");
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /responseHeaders HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("ResponseHeader: value1, value2, -"));
    }

    @Test
    public void testLogQueryString() throws Exception
    {
        start("QueryString: %q");

        HttpTester.Response response = getResponse("GET /path?queryString HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("QueryString: ?queryString"));
    }

    @Test
    public void testLogEmptyQueryString() throws Exception
    {
        start("QueryString: %q");

        HttpTester.Response response = getResponse("GET /path HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("QueryString: -"));
    }

    @Test
    public void testLogRequestFirstLine() throws Exception
    {
        start("RequestFirstLine: %r");

        HttpTester.Response response = getResponse("""
            GET /path?query HTTP/1.0
            Header: null

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestFirstLine: GET /path?query HTTP/1.0"));
    }

    @Test
    public void testLogResponseStatus() throws Exception
    {
        start("LogResponseStatus: %s", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String status = request.getHeaders().get("Status");
                response.setStatus(Integer.parseInt(status));
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("""
            GET /path HTTP/1.0
            Status: 404

            """);
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 404"));

        response = getResponse("""
            GET /path HTTP/1.0
            Status: 301

            """);
        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 301"));

        response = getResponse("""
            GET /path HTTP/1.0
            Status: 200

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 200"));
    }

    @Test
    public void testLogRequestTime() throws Exception
    {
        AtomicLong requestTimeRef = new AtomicLong();
        start("RequestTime: %t", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                requestTimeRef.set(Request.getTimeStamp(request));
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        DateCache dateCache = new DateCache(CustomRequestLog.DEFAULT_DATE_FORMAT, Locale.getDefault(), "GMT");
        assertThat(log, is("RequestTime: [" + dateCache.format(requestTimeRef.get()) + "]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%{EEE MMM dd HH:mm:ss zzz yyyy}t", "%{EEE MMM dd HH:mm:ss zzz yyyy|America/New_York}t", "%{EEE MMM dd HH:mm:ss zzz yyyy|America/New_York|ja}t"})
    public void testLogRequestTimeCustomFormats(String format) throws Exception
    {
        AtomicLong requestTimeRef = new AtomicLong();
        start(format, new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                requestTimeRef.set(System.currentTimeMillis());
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);

        String[] formats = format.substring(format.indexOf('{') + 1, format.indexOf('}')).split("\\|");

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(formats[0])
            .withLocale(formats.length > 2 ? Locale.forLanguageTag(formats[2]) : Locale.getDefault())
            .withZone(formats.length > 1 ? TimeZone.getTimeZone(formats[1]).toZoneId() : TimeZone.getDefault().toZoneId());

        Instant parsed = dateTimeFormatter.parse(log.substring(log.indexOf('[') + 1, log.indexOf(']')), Instant::from);
        Instant request = Instant.ofEpochMilli(requestTimeRef.get());
        assertThat(Math.abs(Duration.between(parsed, request).toSeconds()), Matchers.lessThanOrEqualTo(2L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us", "ms", "s"})
    public void testLogLatency(String unit) throws Exception
    {
        long delay = 1500;
        AtomicLong requestTimeRef = new AtomicLong();
        start("%{" + unit + "}T", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                requestTimeRef.set(request.getBeginNanoTime());
                Thread.sleep(delay);
                callback.succeeded();
                return true;
            }
        });

        TimeUnit timeUnit = switch (unit)
        {
            case "us" -> TimeUnit.MICROSECONDS;
            case "ms" -> TimeUnit.MILLISECONDS;
            case "s" -> TimeUnit.SECONDS;
            default -> throw new IllegalArgumentException("invalid latency unit: " + unit);
        };

        HttpTester.Response response = getResponse("GET /delay HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);

        long lowerBound = delay / 2;
        long upperBound = TimeUnit.MICROSECONDS.convert(NanoTime.since(requestTimeRef.get()), TimeUnit.NANOSECONDS);

        long measuredDuration = Long.parseLong(log);
        long durationLowerBound = timeUnit.convert(lowerBound, TimeUnit.MILLISECONDS);
        long durationUpperBound = timeUnit.convert(upperBound, TimeUnit.MILLISECONDS);

        assertThat(measuredDuration, greaterThanOrEqualTo(durationLowerBound));
        assertThat(measuredDuration, lessThanOrEqualTo(durationUpperBound));
    }

    @Test
    public void testLogUrlRequestPath() throws Exception
    {
        start("UrlRequestPath: %U");

        HttpTester.Response response = getResponse("GET /path?query HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("UrlRequestPath: /path"));
    }

    @Test
    public void testLogConnectionStatus() throws Exception
    {
        start("%U ConnectionStatus: %s %X", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /one HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("/one ConnectionStatus: 200 -"));

        response = getResponse("""
            GET /two HTTP/1.1
            Host: localhost
            Connection: close

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("/two ConnectionStatus: 200 -"));

        getResponses("""
            GET /three HTTP/1.0
            Connection: keep-alive

            GET /four HTTP/1.1
            Host: localhost

            GET /five HTTP/1.1
            Host: localhost
            Connection: close

            """, 3);

        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("/three ConnectionStatus: 200 +"));
        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("/four ConnectionStatus: 200 +"));
        assertThat(_logs.poll(5, TimeUnit.SECONDS), is("/five ConnectionStatus: 200 -"));

        response = getResponse("""
            GET /no/host HTTP/1.1

            """);
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
        log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("/no/host ConnectionStatus: 400 X"));
    }

    @Test
    public void testLogAbortConnectionStatus() throws Exception
    {
        start("%U ConnectionStatus: %s %X", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Callback cbk = Callback.from(() -> callback.failed(new QuietException.Exception("test fail")), callback::failed);
                Content.Sink.write(response, false, "data", cbk);
                return true;
            }
        });

        try (Socket socket = new Socket("localhost", _serverConnector.getLocalPort()))
        {
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);

            OutputStream output = socket.getOutputStream();
            output.write("""
                GET /abort HTTP/1.1
                Host: localhost
                    
                """.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            // Not using HttpTester here because we want to check that last chunk is not received.
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));

            String line = in.readLine();
            assertThat(line, is("HTTP/1.1 200 OK"));
            while (line != null && line.length() > 0)
                line = in.readLine();

            line = in.readLine();
            assertThat("chunk", line, is("4"));
            line = in.readLine();
            assertThat("data", line, is("data"));
            line = in.readLine();
            // This is the crucial part of the test. abort is indicated by no last chunk.
            assertThat(line, nullValue());
        }
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("/abort ConnectionStatus: 200 X"));
    }

    @Test
    public void testLogRequestTrailer() throws Exception
    {
        start("%{trailerName}ti", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.1
            Host: localhost
            Transfer-Encoding: chunked
            
            0
            trailerName: 42
            
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("42"));
    }

    @Test
    public void testLogResponseTrailer() throws Exception
    {
        start("%{trailerName}to", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                HttpFields.Mutable trailers = HttpFields.build();
                response.setTrailersSupplier(() -> trailers);
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                trailers.put("trailerName", "42");
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET / HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("42"));
    }

    @Test
    public void testLogRequestHttpUri() throws Exception
    {
        start("%uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("http://127.0.0.1:" + _serverConnector.getLocalPort() + "/path?hello=world#fragment"));
    }

    @Test
    public void testLogRequestHttpUriWithoutQuery() throws Exception
    {
        start("%{-query}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("http://127.0.0.1:" + _serverConnector.getLocalPort() + "/path"));
    }

    @Test
    public void testLogRequestHttpUriWithoutQueryAndPath() throws Exception
    {
        start("%{-path,-query}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("http://127.0.0.1:" + _serverConnector.getLocalPort()));
    }

    @Test
    public void testLogRequestHttpUriHost() throws Exception
    {
        start("%{host}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("127.0.0.1"));
    }

    @Test
    public void testLogRequestHttpUriPort() throws Exception
    {
        start("%{port}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is(Integer.toString(_serverConnector.getLocalPort())));
    }

    @Test
    public void testLogRequestHttpUriScheme() throws Exception
    {
        start("%{scheme}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("http"));
    }

    @Test
    public void testLogRequestHttpUriAuthority() throws Exception
    {
        start("%{authority}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("127.0.0.1:" + _serverConnector.getLocalPort()));
    }

    @Test
    public void testLogRequestHttpUriPath() throws Exception
    {
        start("%{path}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("/path"));
    }

    @Test
    public void testLogRequestHttpUriQuery() throws Exception
    {
        start("%{query}uri", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /path?hello=world#fragment HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("?hello=world"));
    }

    @Test
    public void testLogRequestAttribute() throws Exception
    {
        start("%{myAttribute}attr", new SimpleHandler()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.setAttribute("myAttribute", "value1234");
                Content.Sink.write(response, false, "hello", Callback.NOOP);
                callback.succeeded();
                return true;
            }
        });

        HttpTester.Response response = getResponse("GET /?hello=world HTTP/1.0\n\n");
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("value1234"));
    }

    @Test
    public void testLogBadUserAgent() throws Exception
    {
        start("User-Agent: %{User-Agent}i, \"%{User-Agent}i\"");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            User-Agent: bad"value

            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("User-Agent: \"bad\\\"value\", \"bad\\\"value\""));
    }

    @Test
    public void testLogEscapedUserAgent() throws Exception
    {
        start("User-Agent: %{User-Agent}i, \"%{User-Agent}i\"");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            User-Agent: bad\\"value
            
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("User-Agent: \"bad\\\\\\\"value\", \"bad\\\\\\\"value\""));
    }

    @Test
    public void testLogWithSloshSeparator() throws Exception
    {
        start("User-Agent: %{User-Agent}i\\\"%m\"");

        HttpTester.Response response = getResponse("""
            GET / HTTP/1.0
            User-Agent: jetty
            
            """);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        String log = _logs.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("User-Agent: jetty\\\"GET\""));
    }

    class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            _logs.add(requestEntry);
        }
    }

    private static class SimpleHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            callback.succeeded();
            return true;
        }
    }
}
