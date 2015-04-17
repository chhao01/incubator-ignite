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

package org.apache.ignite.igfs;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.concurrent.*;

/**
 * Provides ability to execute IGFS code in a context of a specific user.
 */
public abstract class IgfsUserContext {
    /** Thread local to hold the current user context. */
    private static final ThreadLocal<String> userStackThreadLocal = new ThreadLocal<>();

    /**
     * Executes given callable in the given user context.
     * The main contract of this method is that {@link #currentUser()} method invoked
     * inside 'cllbl' callable always returns 'user' this callable executed with.
     * @param user the user name
     * @param cllbl the callable to execute
     * @param <T> The type of callable result.
     * @return the result of
     * @throws NullPointerException if callable is null
     * @throws IllegalArgumentException if user name is null or empty String.
     * @throws IgniteException if any Exception thrown from callable.call().
     * The contract is that if this method throws IgniteException, this IgniteException getCause() method
     * must return exactly the Exception thrown from the callable.
     */
    public static <T> T doAs(String user, final Callable<T> cllbl) {
        // TODO: Use A.ensure();
        if (F.isEmpty(user))
            throw new IllegalArgumentException("Failed to use null or empty user name.");

        // TODO: Remove.
        user = user.intern();

        final String ctxUser = userStackThreadLocal.get();

        try {
            // TODO: Equals: F.eq
            if (ctxUser == user)
                return cllbl.call(); // correct context is already there

            userStackThreadLocal.set(user);

            try {
                return cllbl.call();
            }
            finally {
                userStackThreadLocal.set(ctxUser);
            }
        }
        catch (Exception e) {
            throw new IgniteException(e);
        }
    }

    /**
     * Gets the current context user.
     * If this method is invoked outside of any doAs() on call stack, it will return null.
     * Note that the returned user name is always interned, so
     * you may compare the names using '==' reference equality.
     * @return the current user, never null.
     */
    @Nullable public static String currentUser() {
        return userStackThreadLocal.get();
    }

    /**
     * Provides non-null interned user name.
     * If the user name is null or empty string, defaults to {@link FileSystemConfiguration#DFLT_USER_NAME},
     * which is the current process owner user.
     * @param user a user name to be fixed.
     * @return non-null interned user name.
     */
    // TODO: Move to IgfsUtils.
    public static String fixUserName(@Nullable String user) {
        if (F.isEmpty(user))
           user = FileSystemConfiguration.DFLT_USER_NAME;

        return user.intern();
    }
}
