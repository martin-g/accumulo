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
package org.apache.accumulo.monitor.rest.compactions.external;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.accumulo.monitor.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generate a new External compactions resource
 *
 * @since 2.1.0
 */
@Path("/ec")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class ECResource {
  private static Logger log = LoggerFactory.getLogger(ECResource.class);

  @Inject
  private Monitor monitor;

  @GET
  public CoordinatorInfo getCoordinator() {
    var cc = monitor.getCompactorsInfo();
    log.info("Got coordinator from monitor = {}", cc);
    return new CoordinatorInfo(cc.getCoordinatorHost(), cc);
  }

  @Path("compactors")
  @GET
  public Compactors getCompactors() {
    return new Compactors(monitor.getCompactorsInfo());
  }

  @Path("running")
  @GET
  public RunningCompactions getRunning() {
    return new RunningCompactions(monitor.getRunningInfo());
  }
}
