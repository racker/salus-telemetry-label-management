/*
 * Copyright 2019 Rackspace US, Inc.
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

package com.rackspace.salus.telemetry.etcd.types;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class ResolvedZoneTest {

  @Test
  public void getTenantForKey_nonPublic() {
    final ResolvedZone zone = new ResolvedZone()
        .setPublicZone(false)
        .setTenantId("tenant-1");

    assertThat(zone.getTenantForKey(), equalTo("tenant-1"));
  }

  @Test
  public void getTenantForKey_public() {
    final ResolvedZone zone = new ResolvedZone()
        .setPublicZone(true)
        .setTenantId("tenant-1");

    assertThat(zone.getTenantForKey(), equalTo(ResolvedZone.PUBLIC));
  }

  @Test
  public void getTenantAlwaysNullForPublic() {
    final ResolvedZone zone = new ResolvedZone()
        .setPublicZone(true)
        .setTenantId("tenant-1");

    assertThat(zone.getTenantId(), nullValue());
  }

  @Test
  public void getZoneIdForKey() {
    final ResolvedZone zone = new ResolvedZone()
        .setId("companyName/west")
        .setPublicZone(true)
        .setTenantId("tenant-1");

    assertThat(zone.getZoneIdForKey(), equalTo("companyName%2Fwest"));
  }
}