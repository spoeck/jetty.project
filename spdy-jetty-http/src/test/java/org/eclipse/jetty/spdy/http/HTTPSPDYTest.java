/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.SPDYClient;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

public class HTTPSPDYTest
{
    @Rule
    public final TestWatchman testName = new TestWatchman()
    {
        @Override
        public void starting(FrameworkMethod method)
        {
            super.starting(method);
            System.err.printf("Running %s.%s()%n",
                    method.getMethod().getDeclaringClass().getName(),
                    method.getName());
        }
    };

    protected Server server;
    protected SPDYClient.Factory clientFactory;
    protected SPDYServerConnector connector;

    protected InetSocketAddress startHTTPServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = newHTTPSPDYServerConnector();
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SPDYServerConnector newHTTPSPDYServerConnector()
    {
        return new HTTPSPDYServerConnector();
    }

    protected Session startClient(InetSocketAddress socketAddress, Session.FrameListener frameListener) throws Exception
    {
        if (clientFactory == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(threadPool.getName() + "-client");
            clientFactory = newSPDYClientFactory(threadPool);
            clientFactory.start();
        }
        return clientFactory.newSPDYClient().connect(socketAddress, frameListener).get();
    }

    protected SPDYClient.Factory newSPDYClientFactory(ThreadPool threadPool)
    {
        return new SPDYClient.Factory(threadPool);
    }

    @After
    public void destroy() throws Exception
    {
        if (clientFactory != null)
        {
            clientFactory.stop();
            clientFactory.join();
        }
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    @Ignore
    @Test
    public void test100Continue() throws Exception
    {
        final String data = "data";
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.sendError(100);

                BufferedReader reader = httpRequest.getReader();
                String read = reader.readLine();
                Assert.assertEquals(data, read);
                Assert.assertNull(reader.readLine());

                httpResponse.setStatus(200);
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + "/100");
        headers.put("version", "HTTP/1.1");
        headers.put("expect", "100-continue");

        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.getHeaders().get("status").value().contains("100"));
                replyLatch.countDown();

                // Now send the data
                stream.data(new StringDataInfo(data, true));
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(500_000);
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("GET", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                Assert.assertEquals("localhost:" + connector.getLocalPort(), httpRequest.getHeader("host"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithQueryString() throws Exception
    {
        final String path = "/foo";
        final String query = "p=1";
        final String uri = path + "?" + query;
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("GET", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                Assert.assertEquals(query, httpRequest.getQueryString());
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + uri);
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHEAD() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("HEAD", httpRequest.getMethod());
                Assert.assertEquals(path, target);
                Assert.assertEquals(path, httpRequest.getRequestURI());
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "HEAD");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParameters() throws Exception
    {
        final String path = "/foo";
        final String data = "a=1&b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(SPDY.V2, new SynInfo(headers, false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        stream.data(new StringDataInfo(data, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesTwoReads() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(SPDY.V2, new SynInfo(headers, false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        // Sleep between the data frames so that they will be read in 2 reads
        stream.data(new StringDataInfo(data1, false));
        Thread.sleep(1000);
        stream.data(new StringDataInfo(data2, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTWithParametersInTwoFramesOneRead() throws Exception
    {
        final String path = "/foo";
        final String data1 = "a=1&";
        final String data2 = "b=2";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals("POST", httpRequest.getMethod());
                Assert.assertEquals("1", httpRequest.getParameter("a"));
                Assert.assertEquals("2", httpRequest.getParameter("b"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "POST");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        headers.put("content-type", "application/x-www-form-urlencoded");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(SPDY.V2, new SynInfo(headers, false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        // Send the data frames consecutively, so the server reads both frames in one read
        stream.data(new StringDataInfo(data1, false));
        stream.data(new StringDataInfo(data2, true));

        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContent() throws Exception
    {
        final String data = "0123456789ABCDEF";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + "/foo");
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();
                Assert.assertEquals(data, Charset.forName("UTF-8").decode(buffer).toString());
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInOneWrite() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'x');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data);
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + "/foo");
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();
                Assert.assertEquals(data, Charset.forName("UTF-8").decode(buffer).toString());
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithBigResponseContentInTwoWrites() throws Exception
    {
        // TODO
        Assert.fail();

        final byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'x');
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data);
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + "/foo");
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();
                Assert.assertEquals(data, Charset.forName("UTF-8").decode(buffer).toString());
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETWithSmallResponseContentInTwoChunks() throws Exception
    {
        final String data1 = "0123456789ABCDEF";
        final String data2 = "FEDCBA9876543210";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(data1.getBytes("UTF-8"));
                output.flush();
                output.write(data2.getBytes("UTF-8"));
                handlerLatch.countDown();
            }
        }), null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + "/foo");
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                Assert.assertTrue(dataInfo.isClose());
                ByteBuffer buffer = ByteBuffer.allocate(dataInfo.getBytesCount());
                dataInfo.getBytes(buffer);
                buffer.flip();
                Assert.assertEquals(data1, Charset.forName("UTF-8").decode(buffer).toString());
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(500, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    // TODO: add tests for chunked content

    // Note that I do not care much about the state of the generator, as long as I can avoid
    // that the generator writes, that SPDY writes chunked bytes, and - if possible - data copying
}
