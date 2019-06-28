/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.DoneableSecret;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

/**
 * Operations for {@code Secret}s.
 */
public class SecretOperator extends AbstractResourceOperator<KubernetesClient, Secret, SecretList, DoneableSecret, Resource<Secret, DoneableSecret>> {

    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     * @param operationTimeoutMs Timeout
     */
    public SecretOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs) {
        super(vertx, client, "Secret", operationTimeoutMs);
    }

    @Override
    protected MixedOperation<Secret, SecretList, DoneableSecret, Resource<Secret, DoneableSecret>> operation() {
        return client.secrets();
    }
}
