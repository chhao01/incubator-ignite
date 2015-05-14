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

package org.apache.ignite.marshaller.optimized;

import org.apache.ignite.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.io.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.marshaller.*;
import org.jetbrains.annotations.*;
import org.jsr166.*;
import sun.misc.*;

import java.io.*;
import java.nio.*;
import java.util.concurrent.*;

/**
 * Optimized implementation of {@link org.apache.ignite.marshaller.Marshaller}. Unlike {@link org.apache.ignite.marshaller.jdk.JdkMarshaller},
 * which is based on standard {@link ObjectOutputStream}, this marshaller does not
 * enforce that all serialized objects implement {@link Serializable} interface. It is also
 * about 20 times faster as it removes lots of serialization overhead that exists in
 * default JDK implementation.
 * <p>
 * {@code OptimizedMarshaller} is tested only on Java HotSpot VM on other VMs
 * it could yield unexpected results. It is the default marshaller on Java HotSpot VMs
 * and will be used if no other marshaller was explicitly configured.
 * <p>
 * <h1 class="header">Configuration</h1>
 * <h2 class="header">Mandatory</h2>
 * This marshaller has no mandatory configuration parameters.
 * <h2 class="header">Java Example</h2>
 * <pre name="code" class="java">
 * OptimizedMarshaller marshaller = new OptimizedMarshaller();
 *
 * // Enforce Serializable interface.
 * marshaller.setRequireSerializable(true);
 *
 * IgniteConfiguration cfg = new IgniteConfiguration();
 *
 * // Override marshaller.
 * cfg.setMarshaller(marshaller);
 *
 * // Starts grid.
 * G.start(cfg);
 * </pre>
 * <h2 class="header">Spring Example</h2>
 * GridOptimizedMarshaller can be configured from Spring XML configuration file:
 * <pre name="code" class="xml">
 * &lt;bean id="grid.custom.cfg" class="org.apache.ignite.configuration.IgniteConfiguration" singleton="true"&gt;
 *     ...
 *     &lt;property name="marshaller"&gt;
 *         &lt;bean class="org.apache.ignite.marshaller.optimized.OptimizedMarshaller"&gt;
 *             &lt;property name="requireSerializable"&gt;true&lt;/property&gt;
 *         &lt;/bean&gt;
 *     &lt;/property&gt;
 *     ...
 * &lt;/bean&gt;
 * </pre>
 * <p>
 * <img src="http://ignite.incubator.apache.org/images/spring-small.png">
 * <br>
 * For information about Spring framework visit <a href="http://www.springframework.org/">www.springframework.org</a>
 */
public class OptimizedMarshaller extends AbstractMarshaller {
    /** Default initial buffer size. */
    private static final int DFLT_INIT_BUF_SIZE = 16;

    /** Default class loader. */
    private final ClassLoader dfltClsLdr = getClass().getClassLoader();

    /** Whether or not to require an object to be serializable in order to be marshalled. */
    private boolean requireSer = true;

    /** Initial buffer size. */
    private int initBufSize = DFLT_INIT_BUF_SIZE;

    /** ID mapper. */
    private MarshallerIdMapper mapper;

    /** Class descriptors by class. */
    private final ConcurrentMap<Class, OptimizedClassDescriptor> clsMap = new ConcurrentHashMap8<>();

    /**
     * Creates new marshaller will all defaults.
     *
     * @throws IgniteException If this marshaller is not supported on the current JVM.
     */
    public OptimizedMarshaller() {
        if (!available())
            throw new IgniteException("Using OptimizedMarshaller on unsupported JVM version (some of " +
                "JVM-private APIs required for the marshaller to work are missing).");
    }

    /**
     * Creates new marshaller providing whether it should
     * require {@link Serializable} interface or not.
     *
     * @param requireSer Whether to require {@link Serializable}.
     */
    public OptimizedMarshaller(boolean requireSer) {
        this();

        this.requireSer = requireSer;
    }

    /**
     * Sets whether marshaller should require {@link Serializable} interface or not.
     *
     * @param requireSer Whether to require {@link Serializable}.
     */
    public void setRequireSerializable(boolean requireSer) {
        this.requireSer = requireSer;
    }

    /**
     * Sets initial buffer size.
     *
     * @param initBufSize Initial buffer size.
     */
    public void setInitialBufferSize(int initBufSize) {
        this.initBufSize = initBufSize;
    }

    /**
     * Sets ID mapper.
     *
     * @param mapper ID mapper.
     */
    public void setIdMapper(MarshallerIdMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override public void marshal(@Nullable Object obj, OutputStream out) throws IgniteCheckedException {
        assert out != null;

        OptimizedObjectOutputStream objOut = null;

        try {
            // TODO: https://issues.apache.org/jira/browse/IGNITE-893
            objOut = new OptimizedObjectOutputStream(new GridUnsafeDataOutput(initBufSize));

            objOut.context(clsMap, ctx, mapper, requireSer);

            objOut.out().outputStream(out);

            objOut.writeObject(obj);
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to serialize object: " + obj, e);
        }
        finally {
            U.closeQuiet(objOut);
        }
    }

    /** {@inheritDoc} */
    @Override public ByteBuffer marshal(@Nullable Object obj) throws IgniteCheckedException {
        return marshal(obj, 0);
    }

    /**
     * Marshals object to bytes.
     *
     * @param obj Object to marshal.
     * @param offset Position where to start marshalling the object.
     * @return Byte buffer.
     * @throws IgniteCheckedException If marshalling failed.
     */
    public ByteBuffer marshal(@Nullable Object obj, int offset) throws IgniteCheckedException {
        OptimizedObjectOutputStream objOut = null;

        try {
            // TODO: https://issues.apache.org/jira/browse/IGNITE-893
            objOut = new OptimizedObjectOutputStream(new GridUnsafeDataOutput(initBufSize));

            objOut.context(clsMap, ctx, mapper, requireSer);

            objOut.writeObject(obj);

            ByteBuffer buffer = ByteBuffer.allocate(objOut.out().offset() + offset);

            buffer.position(offset);

            buffer.put(objOut.out().internalArray(), 0, objOut.out().offset());

            buffer.flip();

            return buffer;
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to serialize object: " + obj, e);
        }
        finally {
            U.closeQuiet(objOut);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <T> T unmarshal(InputStream in, @Nullable ClassLoader clsLdr) throws IgniteCheckedException {
        assert in != null;

        OptimizedObjectInputStream objIn = null;

        try {
            objIn = new OptimizedObjectInputStream(new GridUnsafeDataInput());

            objIn.context(clsMap, ctx, mapper, clsLdr != null ? clsLdr : dfltClsLdr);

            objIn.in().inputStream(in);

            return (T)objIn.readObject();
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to deserialize object with given class loader: " + clsLdr, e);
        }
        catch (ClassNotFoundException e) {
            throw new IgniteCheckedException("Failed to find class with given class loader for unmarshalling " +
                "(make sure same versions of all classes are available on all nodes or enable peer-class-loading): " +
                clsLdr, e);
        }
        finally {
            U.closeQuiet(objIn);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <T> T unmarshal(ByteBuffer buf, @Nullable ClassLoader clsLdr) throws IgniteCheckedException {
        assert buf != null;
        assert buf.hasArray();

        OptimizedObjectInputStream objIn = null;

        try {
            objIn = new OptimizedObjectInputStream(new GridUnsafeDataInput());

            objIn.context(clsMap, ctx, mapper, clsLdr != null ? clsLdr : dfltClsLdr);

            objIn.in().bytes(buf.array(), buf.position(), buf.remaining());

            return (T)objIn.readObject();
        }
        catch (IOException e) {
            throw new IgniteCheckedException("Failed to deserialize object with given class loader: " + clsLdr, e);
        }
        catch (ClassNotFoundException e) {
            throw new IgniteCheckedException("Failed to find class with given class loader for unmarshalling " +
                "(make sure same version of all classes are available on all nodes or enable peer-class-loading): " +
                clsLdr, e);
        }
        finally {
            U.closeQuiet(objIn);
        }
    }

    /**
     * Checks whether {@code GridOptimizedMarshaller} is able to work on the current JVM.
     * <p>
     * As long as {@code GridOptimizedMarshaller} uses JVM-private API, which is not guaranteed
     * to be available on all JVM, this method should be called to ensure marshaller could work properly.
     * <p>
     * Result of this method is automatically checked in constructor.
     *
     * @return {@code true} if {@code GridOptimizedMarshaller} can work on the current JVM or
     *  {@code false} if it can't.
     */
    @SuppressWarnings({"TypeParameterExtendsFinalClass", "ErrorNotRethrown"})
    public static boolean available() {
        try {
            Unsafe unsafe = GridUnsafe.unsafe();

            Class<? extends Unsafe> unsafeCls = unsafe.getClass();

            unsafeCls.getMethod("allocateInstance", Class.class);
            unsafeCls.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class);

            return true;
        }
        catch (Exception ignored) {
            return false;
        }
        catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    /**
     * Undeployment callback invoked when class loader is being undeployed.
     *
     * @param ldr Class loader being undeployed.
     */
    public void onUndeploy(ClassLoader ldr) {
        for (Class<?> cls : clsMap.keySet()) {
            if (ldr.equals(cls.getClassLoader()))
                clsMap.remove(cls);
        }

        U.clearClassCache(ldr);
    }
}
