/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.core.iteratorsImpl.system;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;

/**
 * Allows an iterator to be interrupted. Typically, once the interrupt flag is set the iterator will
 * throw an {@link InterruptedException} if the interrupt is detected. Some iterators have been
 * optimized to not always check the flag.
 * <p>
 * One example of a system interrupt is when a Tablet is being closed. If a Tablet has an active
 * scan and an InterruptibleIterator is configured on that Table, then it will be interrupted when
 * the Tablet is closed.
 */
public interface InterruptibleIterator extends SortedKeyValueIterator<Key,Value> {
  void setInterruptFlag(AtomicBoolean flag);
}
