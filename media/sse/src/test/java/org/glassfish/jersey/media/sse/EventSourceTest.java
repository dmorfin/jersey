/*
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.media.sse;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertTrue;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

/**
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class EventSourceTest extends JerseyTest {

    private static String testHeader;

    private static final String QUERY = "!@#$%^&()";

    /**
     * The test test that SSE connection is really closed when EventSource.close() is called.
     * <p/>
     * This test is very HttpURLConnection and Grizzly server specific, so it will probably fail, if other client and server
     * transport are used.
     */
    @Test
    public void testThreadName() throws InterruptedException {
        WebTarget sseTarget = target("sse").queryParam("test", QUERY);

        CountDownLatch eventLatch = new CountDownLatch(1);
        EventSource eventSource = new EventSource(sseTarget) {
            @Override
            public void onEvent(final InboundEvent inboundEvent) {
                String name = Thread.currentThread().getName();
                try {
                    if (name.contains(URLEncoder.encode(QUERY, "ASCII"))) {
                        eventLatch.countDown();
                    }
                } catch (UnsupportedEncodingException e) {
                    // ignore.
                }
            }
        };

        assertEquals("OK", target("sse/send").request().get().readEntity(String.class));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));

        // After receiving the event, we try to close.
        eventSource.close();
    }

    @Test
    public void testCorrectLastEventId() throws InterruptedException {
        WebTarget sseTarget = target("sse").queryParam("test", QUERY);
        testHeader = "";
        CountDownLatch eventLatch = new CountDownLatch(1);
        EventSource eventSource = new EventSource(sseTarget, false) {
            @Override
            public void onEvent(final InboundEvent inboundEvent) {
                String name = Thread.currentThread().getName();
                try {
                    if (name.contains(URLEncoder.encode(QUERY, "ASCII"))) {
                        eventLatch.countDown();
                    }
                } catch (UnsupportedEncodingException e) {
                    // ignore.
                }
            }
        };
        eventSource.open("1");
        assertEquals("OK", target("sse/send").request().get().readEntity(String.class));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
        assertEquals("1", testHeader);

        // After receiving the event, we try to close.
        eventSource.close();
    }

    @Override
    protected Application configure() {
        return new ResourceConfig(EventSourceTest.SseEndpoint.class);
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.register(SseFeature.class);
    }

    @Singleton
    @Path("sse")
    public static class SseEndpoint {

        private final EventOutput eventOutput = new EventOutput();

        @GET
        @Path("send")
        public String sendEvent() throws InterruptedException {
            OutboundEvent event = new OutboundEvent.Builder().data("An event").build();
            try {
                if (eventOutput.isClosed()) {
                    return "NOK";
                }

                eventOutput.write(event);
            } catch (IOException e) {
                return "NOK";
            }

            return "OK";
        }

        @GET
        @Produces(SseFeature.SERVER_SENT_EVENTS)
        public EventOutput get(@Context HttpHeaders httpHeaders) {
            testHeader = httpHeaders.getHeaderString(SseFeature.LAST_EVENT_ID_HEADER);
            return eventOutput;
        }
    }
}
