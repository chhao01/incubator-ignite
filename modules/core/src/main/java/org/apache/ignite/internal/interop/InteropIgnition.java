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

package org.apache.ignite.internal.interop;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.resource.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.lang.ref.*;
import java.net.*;
import java.security.*;
import java.util.*;

/**
 * Entry point for interop nodes.
 */
@SuppressWarnings("UnusedDeclaration")
public class InteropIgnition {
    /**
     * Start Ignite node in interop mode.
     *
     * @param springCfgPath Spring configuration path.
     * @param gridName Grid name.
     * @param factoryId Factory ID.
     * @param envPtr Environment pointer.
     * @return Ignite instance.
     */
    public static InteropProcessor start(@Nullable String springCfgPath, @Nullable String gridName, int factoryId,
        long envPtr) {
        IgniteConfiguration cfg = configuration(springCfgPath);

        if (gridName != null)
            cfg.setGridName(gridName);

        InteropBootstrap bootstrap = bootstrap(factoryId);

        InteropProcessor proc = bootstrap.start(cfg, envPtr);

        trackFinalization(proc);

        return proc;
    }

    /**
     * Create configuration.
     *
     * @param springCfgPath Path to Spring XML.
     * @return Configuration.
     */
    private static IgniteConfiguration configuration(@Nullable String springCfgPath) {
        try {
            URL url = springCfgPath == null ? U.resolveIgniteUrl(IgnitionEx.DFLT_CFG) :
                U.resolveSpringUrl(springCfgPath);

            IgniteBiTuple<IgniteConfiguration, GridSpringResourceContext> t = IgnitionEx.loadConfiguration(url);

            return t.get1();
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to instantiate configuration from Spring XML: " + springCfgPath, e);
        }
    }

    /**
     * Create bootstrap for the given factory ID.
     *
     * @param factoryId Factory ID.
     * @return Bootstrap.
     */
    private static InteropBootstrap bootstrap(final int factoryId) {
        InteropBootstrapFactory factory = AccessController.doPrivileged(
            new PrivilegedAction<InteropBootstrapFactory>() {
            @Override public InteropBootstrapFactory run() {
                for (InteropBootstrapFactory factory : ServiceLoader.load(InteropBootstrapFactory.class)) {
                    if (factory.id() == factoryId)
                        return factory;
                }

                return null;
            }
        });

        if (factory == null)
            throw new IgniteException("Interop factory is not found (did you put into the classpath?): " + factoryId);

        return factory.create();
    }

    /**
     * Track processor finalization.
     *
     * @param proc Processor.
     */
    private static void trackFinalization(InteropProcessor proc) {
        Thread thread = new Thread(new Finalizer(proc));

        thread.setDaemon(true);

        thread.start();
    }

    /**
     * Finalizer runnable.
     */
    private static class Finalizer implements Runnable {
        /** Queue where we expect notification to appear. */
        private final ReferenceQueue<InteropProcessor> queue;

        /** Phantom reference to processor.  */
        private final PhantomReference<InteropProcessor> proc;

        /** Cleanup runnable. */
        private final Runnable cleanup;

        /**
         * Constructor.
         *
         * @param proc Processor.
         */
        public Finalizer(InteropProcessor proc) {
            queue = new ReferenceQueue<>();

            this.proc = new PhantomReference<>(proc, queue);

            cleanup = proc.cleanupCallback();
        }

        /** {@inheritDoc} */
        @Override public void run() {
            try {
                queue.remove(0);

                cleanup.run();
            }
            catch (InterruptedException ignore) {
                // No-op.
            }
        }
    }

    /**
     * Private constructor.
     */
    private InteropIgnition() {
        // No-op.
    }
}
