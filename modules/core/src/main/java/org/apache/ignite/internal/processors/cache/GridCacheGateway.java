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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

/**
 * Cache gateway.
 */
@GridToStringExclude
public class GridCacheGateway<K, V> {
    /** Context. */
    private final GridCacheContext<K, V> ctx;

    /** Stopped flag for dynamic caches. */
    private volatile boolean stopped;

    /** */
    private GridSpinReadWriteLock rwLock = new GridSpinReadWriteLock();

    /**
     * @param ctx Cache context.
     */
    public GridCacheGateway(GridCacheContext<K, V> ctx) {
        assert ctx != null;

        this.ctx = ctx;
    }

    /**
     * Enter a cache call.
     */
    public void enter() {
        if (ctx.deploymentEnabled())
            ctx.deploy().onEnter();

        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            throw new IllegalStateException("Dynamic cache has been stopped: " + ctx.name());
        }
    }

    /**
     * Enter a cache call.
     *
     * @return {@code True} if enter successful, {@code false} if the cache or the node was stopped.
     */
    public boolean enterIfNotClosed() {
        onEnter();

        // Must unlock in case of unexpected errors to avoid
        // deadlocks during kernal stop.
        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            return false;
        }

        return true;
    }

    /**
     * Enter a cache call without lock.
     *
     * @return {@code True} if enter successful, {@code false} if the cache or the node was stopped.
     */
    public boolean enterIfNotClosedNoLock() {
        onEnter();

        return !stopped;
    }

    /**
     * Leave a cache call entered by {@link #enterNoLock} method.
     */
    public void leaveNoLock() {
        ctx.tm().resetContext();
        ctx.mvcc().contextReset();

        // Unwind eviction notifications.
        if (!ctx.shared().closed(ctx))
            CU.unwindEvicts(ctx);
    }

    /**
     * Leave a cache call entered by {@link #enter()} method.
     */
    public void leave() {
        try {
           leaveNoLock();
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * @param opCtx Cache operation context to guard.
     * @return Previous operation context set on this thread.
     */
    @Nullable public CacheOperationContext enter(@Nullable CacheOperationContext opCtx) {
        try {
            GridCacheAdapter<K, V> cache = ctx.cache();

            GridCachePreloader preldr = cache != null ? cache.preloader() : null;

            if (preldr == null)
                throw new IllegalStateException("Grid is in invalid state to perform this operation. " +
                    "It either not started yet or has already being or have stopped [gridName=" + ctx.gridName() + ']');

            preldr.startFuture().get();
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to wait for cache preloader start [cacheName=" +
                ctx.name() + "]", e);
        }

        onEnter();

        rwLock.readLock();

        if (stopped) {
            rwLock.readUnlock();

            throw new IllegalStateException("Cache has been stopped: " + ctx.name());
        }

        // Must unlock in case of unexpected errors to avoid
        // deadlocks during kernal stop.
        try {
            return setOperationContextPerCall(opCtx);
        }
        catch (RuntimeException e) {
            rwLock.readUnlock();

            throw e;
        }
    }

    /**
     * @param opCtx Operation context to guard.
     * @return Previous operation context set on this thread.
     */
    @Nullable public CacheOperationContext enterNoLock(@Nullable CacheOperationContext opCtx) {
        onEnter();

        if (stopped)
            throw new IllegalStateException("Cache has been stopped: " + ctx.name());

        return setOperationContextPerCall(opCtx);
    }

    /**
     * Set thread local operation context per call.
     *
     * @param opCtx Operation context to guard.
     * @return Previous operation context set on this thread.
     */
    private CacheOperationContext setOperationContextPerCall(@Nullable CacheOperationContext opCtx) {
        CacheOperationContext prev = ctx.operationContextPerCall();

        if (prev != null || opCtx != null)
            ctx.operationContextPerCall(opCtx);

        return prev;
    }

    /**
     * @param prev Previous.
     */
    public void leave(CacheOperationContext prev) {
        try {
            leaveNoLock(prev);
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * @param prev Previous.
     */
    public void leaveNoLock(CacheOperationContext prev) {
        ctx.tm().resetContext();
        ctx.mvcc().contextReset();

        // Unwind eviction notifications.
        CU.unwindEvicts(ctx);

        // Return back previous thread local operation context per call.
        ctx.operationContextPerCall(prev);
    }

    /**
     *
     */
    private void onEnter() {
        ctx.itHolder().checkWeakQueue();

        if (ctx.deploymentEnabled())
            ctx.deploy().onEnter();
    }

    /**
     *
     */
    public void block() {
        stopped = true;
    }

    /**
     *
     */
    public void onStopped() {
        boolean interrupted = false;

        while (true) {
            if (rwLock.tryWriteLock())
                break;
            else {
                try {
                    U.sleep(200);
                }
                catch (IgniteInterruptedCheckedException ignore) {
                    interrupted = true;
                }
            }
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        try {
            // No-op.
            stopped = true;
        }
        finally {
            rwLock.writeUnlock();
        }
    }
}
