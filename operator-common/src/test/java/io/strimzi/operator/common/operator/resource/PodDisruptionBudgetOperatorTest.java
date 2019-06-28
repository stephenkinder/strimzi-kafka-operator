/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.policy.DoneablePodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudgetBuilder;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudgetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PolicyAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;

import static java.util.Collections.singletonMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PodDisruptionBudgetOperatorTest extends AbstractResourceOperatorTest<KubernetesClient, PodDisruptionBudget, PodDisruptionBudgetList, DoneablePodDisruptionBudget, Resource<PodDisruptionBudget, DoneablePodDisruptionBudget>> {

    @Override
    protected void  mocker(KubernetesClient mockClient, MixedOperation op) {
        PolicyAPIGroupDSL mockPolicy = mock(PolicyAPIGroupDSL.class);
        when(mockPolicy.podDisruptionBudget()).thenReturn(op);
        when(mockClient.policy()).thenReturn(mockPolicy);
    }

    @Override
    protected AbstractResourceOperator<KubernetesClient, PodDisruptionBudget, PodDisruptionBudgetList, DoneablePodDisruptionBudget, Resource<PodDisruptionBudget, DoneablePodDisruptionBudget>> createResourceOperations(Vertx vertx, KubernetesClient mockClient) {
        return new PodDisruptionBudgetOperator(vertx, mockClient, 100);
    }

    @Override
    protected Class<KubernetesClient> clientType() {
        return KubernetesClient.class;
    }

    @Override
    protected Class<? extends Resource> resourceType() {
        return Resource.class;
    }

    @Override
    protected PodDisruptionBudget resource() {
        return new PodDisruptionBudgetBuilder()
                .withNewMetadata()
                    .withName(RESOURCE_NAME)
                    .withNamespace(NAMESPACE)
                    .withLabels(singletonMap("foo", "bar"))
                .endMetadata()
                .withNewSpec()
                    .withNewMaxUnavailable(1)
                .endSpec()
                .build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void createWhenExistsIsAPatch(TestContext context, boolean cascade) {
        PodDisruptionBudget resource = resource();
        Resource mockResource = mock(resourceType());
        when(mockResource.get()).thenReturn(resource);
        when(mockResource.delete()).thenReturn(Boolean.TRUE);
        when(mockResource.watch(any())).thenAnswer(invocation -> {
            Watcher watcher = invocation.getArgument(0);
            watcher.eventReceived(Watcher.Action.DELETED, resource);
            return (Watch) () -> {

            };
        });
        when(mockResource.cascading(cascade)).thenReturn(mockResource);

        NonNamespaceOperation mockNameable = mock(NonNamespaceOperation.class);
        when(mockNameable.withName(matches(resource.getMetadata().getName()))).thenReturn(mockResource);

        MixedOperation mockCms = mock(MixedOperation.class);
        when(mockCms.inNamespace(matches(resource.getMetadata().getNamespace()))).thenReturn(mockNameable);

        KubernetesClient mockClient = mock(clientType());
        mocker(mockClient, mockCms);

        AbstractResourceOperator<KubernetesClient, PodDisruptionBudget, PodDisruptionBudgetList, DoneablePodDisruptionBudget, Resource<PodDisruptionBudget, DoneablePodDisruptionBudget>> op = createResourceOperations(vertx, mockClient);

        Async async = context.async();
        Future<ReconcileResult<PodDisruptionBudget>> fut = op.createOrUpdate(resource());
        fut.setHandler(ar -> {
            if (!ar.succeeded()) {
                ar.cause().printStackTrace();
            }
            context.assertTrue(ar.succeeded());
            verify(mockResource).get();
            verify(mockResource).delete();
            verify(mockResource).create(any());
            verify(mockResource, never()).patch(any());
            verify(mockResource, never()).createNew();
            verify(mockResource, never()).createOrReplace(any());
            async.complete();
        });
    }
}
