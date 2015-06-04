/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.stream.socket;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.marshaller.jdk.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.stream.*;
import org.apache.ignite.testframework.junits.common.*;

import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.events.EventType.*;

/**
 * Tests {@link SocketStreamer}.
 */
public class SocketStreamerSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** Grid count. */
    private final static int GRID_CNT = 3;

    /** Count. */
    private static final int CNT = 500;

    /** Delimiter. */
    private static final byte[] DELIM = new byte[] {0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0};

    /** Port. */
    private static int port;

    /** Ignite. */
    private static Ignite ignite;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration() throws Exception {
        IgniteConfiguration cfg = super.getConfiguration();

        CacheConfiguration ccfg = cacheConfiguration(cfg, null);

        cfg.setCacheConfiguration(ccfg);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        return cfg;
    }


    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        ignite = startGrids(GRID_CNT);
        ignite.<Integer, String>getOrCreateCache(defaultCacheConfiguration());

        try (ServerSocket sock = new ServerSocket(0)) {
            port = sock.getLocalPort();
        }
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ignite.cache(null).clear();
    }

    /**
     * @throws Exception If failed.
     */
    public void testSizeBasedDefaultConverter() throws Exception {
        test(null, null, new Runnable() {
            @Override public void run() {
                try (Socket sock = new Socket(InetAddress.getLocalHost(), port);
                     OutputStream os = new BufferedOutputStream(sock.getOutputStream())) {
                    Marshaller marsh = new JdkMarshaller();

                    for (int i = 0; i < CNT; i++) {
                        byte[] msg = marsh.marshal(new Tuple(i));

                        os.write(msg.length >>> 24);
                        os.write(msg.length >>> 16);
                        os.write(msg.length >>> 8);
                        os.write(msg.length);

                        os.write(msg);
                    }
                }
                catch (IOException | IgniteCheckedException e) {
                    throw new IgniteException(e);
                }
            }
        });
    }

    /**
     * @throws Exception If failed.
     */
    public void testSizeBasedCustomConverter() throws Exception {
        SocketMessageConverter<Tuple> converter = new SocketMessageConverter<Tuple>() {
            @Override public Tuple convert(byte[] msg) {
                int i = (msg[0] & 0xFF) << 24;
                i |= (msg[1] & 0xFF) << 16;
                i |= (msg[2] & 0xFF) << 8;
                i |= msg[3] & 0xFF;

                return new Tuple(i);
            }
        };

        test(converter, null, new Runnable() {
            @Override public void run() {
                try(Socket sock = new Socket(InetAddress.getLocalHost(), port);
                    OutputStream os = new BufferedOutputStream(sock.getOutputStream())) {

                    for (int i = 0; i < CNT; i++) {
                        os.write(0);
                        os.write(0);
                        os.write(0);
                        os.write(4);

                        os.write(i >>> 24);
                        os.write(i >>> 16);
                        os.write(i >>> 8);
                        os.write(i);
                    }
                }
                catch (IOException e) {
                    throw new IgniteException(e);
                }
            }
        });
    }

    /**
     * @throws Exception If failed.
     */
    public void testDelimiterBasedDefaultConverter() throws Exception {
        test(null, DELIM, new Runnable() {
            @Override public void run() {
                try(Socket sock = new Socket(InetAddress.getLocalHost(), port);
                    OutputStream os = new BufferedOutputStream(sock.getOutputStream())) {
                    Marshaller marsh = new JdkMarshaller();

                    for (int i = 0; i < CNT; i++) {
                        byte[] msg = marsh.marshal(new Tuple(i));

                        os.write(msg);
                        os.write(DELIM);
                    }
                }
                catch (IOException | IgniteCheckedException e) {
                    throw new IgniteException(e);
                }
            }
        });

    }

    /**
     * @throws Exception If failed.
     */
    public void testDelimiterBasedCustomConverter() throws Exception {
        SocketMessageConverter<Tuple> converter = new SocketMessageConverter<Tuple>() {
            @Override public Tuple convert(byte[] msg) {
                int i = (msg[0] & 0xFF) << 24;
                i |= (msg[1] & 0xFF) << 16;
                i |= (msg[2] & 0xFF) << 8;
                i |= msg[3] & 0xFF;

                return new Tuple(i);
            }
        };

        test(converter, DELIM, new Runnable() {
            @Override public void run() {
                try(Socket sock = new Socket(InetAddress.getLocalHost(), port);
                    OutputStream os = new BufferedOutputStream(sock.getOutputStream())) {

                    for (int i = 0; i < CNT; i++) {
                        os.write(i >>> 24);
                        os.write(i >>> 16);
                        os.write(i >>> 8);
                        os.write(i);

                        os.write(DELIM);
                    }
                }
                catch (IOException e) {
                    throw new IgniteException(e);
                }
            }
        });
    }

    /**
     * @param converter Converter.
     * @param r Runnable..
     */
    private void test(@Nullable SocketMessageConverter<Tuple> converter, @Nullable byte[] delim, Runnable r) throws Exception
    {
        SocketStreamer<Tuple, Integer, String> sockStmr = null;

        try (IgniteDataStreamer<Integer, String> stmr = ignite.dataStreamer(null)) {

            stmr.allowOverwrite(true);
            stmr.autoFlushFrequency(10);

            sockStmr = new SocketStreamer<>();

            IgniteCache<Integer, String> cache = ignite.cache(null);

            sockStmr.setIgnite(ignite);

            sockStmr.setStreamer(stmr);

            sockStmr.setPort(port);

            sockStmr.setDelimiter(delim);

            sockStmr.setTupleExtractor(new StreamTupleExtractor<Tuple, Integer, String>() {
                @Override public Map.Entry<Integer, String> extract(Tuple msg) {
                    return new IgniteBiTuple<>(msg.key, msg.val);
                }
            });

            if (converter != null)
                sockStmr.setConverter(converter);

            final CountDownLatch latch = new CountDownLatch(CNT);

            IgniteBiPredicate<UUID, CacheEvent> locLsnr = new IgniteBiPredicate<UUID, CacheEvent>() {
                @Override public boolean apply(UUID uuid, CacheEvent evt) {
                    latch.countDown();

                    return true;
                }
            };

            ignite.events(ignite.cluster().forCacheNodes(null)).remoteListen(locLsnr, null, EVT_CACHE_OBJECT_PUT);

            sockStmr.start();

            r.run();

            latch.await();

            assertEquals(CNT, cache.size(CachePeekMode.PRIMARY));

            for (int i = 0; i < CNT; i++)
                assertEquals(Integer.toString(i), cache.get(i));
        }
        finally {
            if (sockStmr != null)
                sockStmr.stop();
        }

    }

    /**
     * Tuple.
     */
    private static class Tuple implements Serializable {
        /** Serial version uid. */
        private static final long serialVersionUID = 0L;

        /** Key. */
        private final int key;

        /** Value. */
        private final String val;

        /**
         * @param key Key.
         */
        Tuple(int key) {
            this.key = key;
            this.val = Integer.toString(key);
        }
    }
}