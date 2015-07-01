/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.inventory.base.spi;

/**
 * This exception is to be thrown by the backends when a commit fails. This exception is then used by the implementation
 * as a trigger for retrying the transaction.
 *
 * <p>Note that this is a checked exception on purpose because the {@link org.hawkular.inventory.base.BaseInventory}
 * uses this for transaction failure recovery and thus must handle it.
 *
 * @author Lukas Krejci
 * @since 0.2.0
 */
public class CommitFailureException extends Exception {
    public CommitFailureException() {
    }

    public CommitFailureException(Throwable cause) {
        super(cause);
    }

    public CommitFailureException(String message) {
        super(message);
    }

    public CommitFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
