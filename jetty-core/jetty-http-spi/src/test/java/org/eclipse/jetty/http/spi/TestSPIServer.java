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

package org.eclipse.jetty.http.spi;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSPIServer
{
    static
    {
        LoggingUtil.init();
    }

    /**
     * Create a server that has a null InetSocketAddress, then
     * bind before using.
     */
    @Test
    public void testUnboundHttpServer() throws Exception
    {

        HttpServer server = null;

        try
        {
            //ensure no InetSocketAddress is passed
            server = new JettyHttpServerProvider().createHttpServer(null, 10);

            final HttpContext httpContext = server.createContext("/",
                exchange ->
                {
                    Headers responseHeaders = exchange.getResponseHeaders();
                    responseHeaders.set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, 0);

                    OutputStream responseBody = exchange.getResponseBody();
                    Headers requestHeaders = exchange.getRequestHeaders();
                    Set<String> keySet = requestHeaders.keySet();
                    for (String key : keySet)
                    {
                        List<String> values = requestHeaders.get(key);
                        String s = key + " = " + values.toString() + "\n";
                        responseBody.write(s.getBytes());
                    }
                    responseBody.close();
                });

            httpContext.setAuthenticator(new BasicAuthenticator("Test")
            {
                @Override
                public boolean checkCredentials(String username, String password)
                {
                    return "username".equals(username) && password.equals("password");
                }
            });

            //now bind one. Use port '0' to let jetty pick the
            //address to bind so this test isn't port-specific
            //and thus is portable and can be run concurrently on CI
            //environments
            server.bind(new InetSocketAddress("localhost", 0), 10);

            server.start();

            //find out the port jetty picked
            Server jetty = ((JettyHttpServer)server).getServer();
            int port = ((NetworkConnector)jetty.getConnectors()[0]).getLocalPort();

            HttpClient client = new HttpClient();
            client.start();

            try
            {
                Request request = client.newRequest("http://localhost:" + port + "/");
                client.getAuthenticationStore().addAuthentication(new BasicAuthentication(URI.create("http://localhost:" + port), "Test", "username", "password"));
                ContentResponse response = request.send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            if (server != null)
                server.stop(5);
        }
    }

    /**
     * Test using a server that is created with a given InetSocketAddress
     */
    @Test
    public void testBoundHttpServer() throws Exception
    {

        HttpServer server = null;

        try
        {
            //use an InetSocketAddress, but use port value of '0' to allow
            //jetty to pick a free port. Ensures test is not tied to specific port number
            //for test portability and concurrency.
            server = new JettyHttpServerProvider().createHttpServer(new
                InetSocketAddress("localhost", 0), 10);

            final HttpContext httpContext = server.createContext("/",
                exchange ->
                {
                    Headers responseHeaders = exchange.getResponseHeaders();
                    responseHeaders.set("Content-Type", "text/plain");
                    responseHeaders.add("Multi-Value", "1");
                    responseHeaders.add("Multi-Value", "2");
                    exchange.sendResponseHeaders(200, 0);

                    OutputStream responseBody = exchange.getResponseBody();
                    Headers requestHeaders = exchange.getRequestHeaders();
                    Set<String> keySet = requestHeaders.keySet();
                    for (String key : keySet)
                    {
                        List<String> values = requestHeaders.get(key);
                        String s = key + " = " + values.toString() + "\n";
                        responseBody.write(s.getBytes());
                    }
                    responseBody.close();
                });

            httpContext.setAuthenticator(new BasicAuthenticator("Test")
            {
                @Override
                public boolean checkCredentials(String username, String password)
                {
                    return "username".equals(username) && password.equals("password");
                }
            });

            server.start();

            //find out the port jetty picked
            Server jetty = ((JettyHttpServer)server).getServer();
            int port = ((NetworkConnector)jetty.getConnectors()[0]).getLocalPort();

            HttpClient client = new HttpClient();
            client.start();

            try
            {
                Request request = client.newRequest("http://localhost:" + port + "/");
                client.getAuthenticationStore().addAuthentication(new BasicAuthentication(URI.create("http://localhost:" + port), "Test", "username", "password"));
                ContentResponse response = request.send();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                String headers = response.getHeaders().asString();
                assertTrue(headers.contains("Multi-value: 2"));
                assertTrue(headers.contains("Multi-value: 1"));
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            if (server != null)
                server.stop(5);
        }
    }
}
