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

package org.eclipse.jetty.alpn.bouncycastle.client;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BouncyCastleHTTP2ClientTest
{
    @Tag("external")
    @Test
    public void testBouncyCastleHTTP2Client() throws Exception
    {
        String host = "webtide.com";
        int port = 443;

        Assumptions.assumeTrue(canConnectTo(host, port));

        /* Required to instantiate a DEFAULT SecureRandom */
        Security.insertProviderAt(new BouncyCastleFipsProvider(), 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setProvider(BouncyCastleJsseProvider.PROVIDER_NAME);

        try (HTTP2Client client = new HTTP2Client())
        {
            client.addBean(sslContextFactory);
            client.start();

            FuturePromise<Session> sessionPromise = new FuturePromise<>();
            client.connect(sslContextFactory, new InetSocketAddress(host, port), new Session.Listener() {}, sessionPromise);
            Session session = sessionPromise.get(15, TimeUnit.SECONDS);

            HttpFields requestFields = HttpFields.build().put("User-Agent", client.getClass().getName() + "/" + Jetty.VERSION);
            MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from("https://" + host + ":" + port + "/"), HttpVersion.HTTP_2, requestFields);
            HeadersFrame headersFrame = new HeadersFrame(metaData, null, true);
            CountDownLatch latch = new CountDownLatch(1);
            session.newStream(headersFrame, new Promise.Adapter<>(), new Stream.Listener()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    System.err.println(frame);
                    if (frame.isEndStream())
                        latch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream stream)
                {
                    Stream.Data data = stream.readData();
                    System.err.println(data);
                    data.release();
                    if (data.frame().isEndStream())
                        latch.countDown();
                    else
                        stream.demand();
                }
            });

            assertTrue(latch.await(15, TimeUnit.SECONDS));
        }
    }

    private boolean canConnectTo(String host, int port)
    {
        try
        {
            new Socket(host, port).close();
            return true;
        }
        catch (Throwable x)
        {
            return false;
        }
    }
}
