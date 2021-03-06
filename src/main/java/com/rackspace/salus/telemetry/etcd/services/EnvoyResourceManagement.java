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

package com.rackspace.salus.telemetry.etcd.services;

import static com.rackspace.salus.telemetry.etcd.EtcdUtils.EXIT_CODE_ETCD_FAILED;
import static com.rackspace.salus.telemetry.etcd.EtcdUtils.buildKey;
import static com.rackspace.salus.telemetry.etcd.EtcdUtils.parseValue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.common.util.KeyHashing;
import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.types.Keys;
import com.rackspace.salus.telemetry.model.ResourceInfo;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EnvoyResourceManagement {

    private final Client etcd;
    private final ObjectMapper objectMapper;
    private final KeyHashing hashing;

    @Autowired
    public EnvoyResourceManagement(Client etcd, ObjectMapper objectMapper, KeyHashing hashing) {
        this.etcd = etcd;
        this.objectMapper = objectMapper;
        this.hashing = hashing;
    }

    /**
     * Creates the /active key within etcd for the connected envoy.
     * /active is created using a lease.
     *
     * Also creates a key under /tenants/../identifiers with no lease specified.
     *
     * @param tenantId The tenant used to authenticate the the envoy.
     * @param envoyId The auto-generated unique string associated to the envoy.
     * @param leaseId The lease used when creating the /active key.
     * @param resourceId The identifier of the resource where the Envoy is running.
     * @param envoyLabels All labels associated with the envoy.
     * @param remoteAddr The address the envoy is connecting from.
     * @return The results of an etcd PUT.
     */
    public CompletableFuture<ResourceInfo> registerResource(String tenantId, String envoyId, long leaseId,
                                                 String resourceId, Map<String, String> envoyLabels,
                                                 SocketAddress remoteAddr) {
        final PutOption putLeaseOption = PutOption.newBuilder()
                .withLeaseId(leaseId)
                .build();

        String resourceKey = String.format("%s:%s", tenantId, resourceId);
        ResourceInfo resourceInfo = new ResourceInfo()
                .setEnvoyId(envoyId)
                .setResourceId(resourceId)
                .setLabels(envoyLabels)
                .setTenantId(tenantId)
                .setAddress((InetSocketAddress) remoteAddr);

        final String resourceKeyHash = hashing.hash(resourceKey);
        final ByteSequence resourceInfoBytes;
        try {
            resourceInfoBytes = ByteSequence.from(objectMapper.writeValueAsBytes(resourceInfo));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to marshal ResourceInfo", e);
        }

        return etcd.getKVClient().put(
                buildKey(Keys.FMT_RESOURCE_ACTIVE_BY_RESOURCE_ID, tenantId, resourceId),
            resourceInfoBytes,
            putLeaseOption
        )
                .thenApply(putResponse -> {
                    if (envoyId == null) {
                        return resourceInfo;
                    }
                    return putResponse;
                })
                .thenCompose(putResponse ->
                        etcd.getKVClient().put(
                                buildKey(Keys.FMT_RESOURCE_ACTIVE_BY_HASH, resourceKeyHash), resourceInfoBytes, putLeaseOption)
                                .thenApply(response -> resourceInfo));
    }

    public CompletableFuture<ResourceInfo> create(String tenantId, ResourceInfo resource) {
        resource.setTenantId(tenantId);
        return registerResource(tenantId,null, 0L, resource.getResourceId(),
                                resource.getLabels(), null);
    }

    public CompletableFuture<ResourceInfo> getOne(String tenantId, String resourceId) {
        ByteSequence key = EtcdUtils.buildKey(Keys.FMT_RESOURCE_ACTIVE_BY_RESOURCE_ID, tenantId, resourceId);
        return etcd.getKVClient().get(key)
                .thenApply(getResponse -> {
                    log.debug("Found {} resources for tenant {} with resourceId {}", getResponse.getKvs().size(), tenantId, resourceId);
                  if (getResponse.getKvs().isEmpty()) {
                    return null;
                  }
                  else {
                    return parseResourceInfo(getResponse.getKvs()).get(0);
                  }
                });
    }

    private List<ResourceInfo> parseResourceInfo(List<KeyValue> kvs) {
        return kvs.stream()
                .map(keyValue -> {
                    try {
                        return parseValue(objectMapper, keyValue, ResourceInfo.class);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse object", e);
                    }
                }).collect(Collectors.toList());
    }

    public CompletableFuture<GetResponse> getResourcesInRange(String prefix, String min, String max) {
        return etcd.getKVClient().get(buildKey(prefix, min),
                GetOption.newBuilder().withRange(buildKey(prefix, max + '\0')).build());
    }

    public Watch.Watcher createWatchOverRange(String prefix, String min, String max, long revision,
                                              Consumer<WatchResponse> onNext) {
      return etcd.getWatchClient().watch(
          buildKey(prefix, min),
          WatchOption.newBuilder().withRange(buildKey(prefix, max + '\0'))
              .withPrevKV(true).withRevision(revision).build(),
          onNext,
          throwable -> handleWatchError(prefix, throwable)
      );
    }

  private void handleWatchError(String prefix, Throwable throwable) {
    log.error("Error during watch of {}", prefix, throwable);
    // Spring will gracefully shutdown via shutdown hook
    System.exit(EXIT_CODE_ETCD_FAILED);
  }
}
