/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.DoneableEndpoints;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

/**
 * Operations for {@code Endpoint}s.
 */
public class EndpointOperator extends AbstractReadyResourceOperator<KubernetesClient, Endpoints, EndpointsList, DoneableEndpoints, Resource<Endpoints, DoneableEndpoints>> {
    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     * @param operationTimeoutMs Timeout
     */
    EndpointOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs) {
        super(vertx, client, "Endpoints", operationTimeoutMs);
    }

    @Override
    protected MixedOperation<Endpoints, EndpointsList, DoneableEndpoints, Resource<Endpoints, DoneableEndpoints>> operation() {
        return client.endpoints();
    }
}
