/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Abstract resource creation, for a generic resource type {@code R}.
 * This class applies the template method pattern, first checking whether the resource exists,
 * and creating it if it does not. It is not an error if the resource did already exist.
 * @param <C> The type of client used to interact with kubernetes.
 * @param <T> The Kubernetes resource type.
 * @param <L> The list variant of the Kubernetes resource type.
 * @param <D> The doneable variant of the Kubernetes resource type.
 * @param <R> The resource operations.
 */
public abstract class AbstractNonNamespacedResourceOperator<C extends KubernetesClient,
        T extends HasMetadata,
        L extends KubernetesResourceList/*<T>*/,
        D extends Doneable<T>,
        R extends Resource<T, D>> {

    protected final Logger log = LogManager.getLogger(getClass());
    protected final Vertx vertx;
    protected final C client;
    protected final String resourceKind;
    private final ResourceSupport resourceSupport;

    /**
     * Constructor.
     * @param vertx The vertx instance.
     * @param client The kubernetes client.
     * @param resourceKind The mind of Kubernetes resource (used for logging).
     * @param operationTimeoutMs Timeout for operations.
     */
    public AbstractNonNamespacedResourceOperator(Vertx vertx, C client, String resourceKind, long operationTimeoutMs) {
        this.vertx = vertx;
        this.resourceSupport = new ResourceSupport(vertx, operationTimeoutMs);
        this.client = client;
        this.resourceKind = resourceKind;
    }

    protected abstract MixedOperation<T, L, D, R> operation();

    /**
     * Asynchronously create or update the given {@code resource} depending on whether it already exists,
     * returning a future for the outcome.
     * If the resource with that name already exists the future completes successfully.
     * @param resource The resource to create.
     * @return A future which completes when the resource was created or updated.
     */
    public Future<ReconcileResult<T>> createOrUpdate(T resource) {
        if (resource == null) {
            throw new NullPointerException();
        }
        return reconcile(resource.getMetadata().getName(), resource);
    }

    /**
     * Asynchronously reconciles the resource with the given name to match the given
     * desired resource, returning a future for the result.
     * @param name The name of the resource to reconcile.
     * @param desired The desired state of the resource.
     * @return A future which completes when the resource was reconciled.
     */
    public Future<ReconcileResult<T>> reconcile(String name, T desired) {

        if (desired != null && !name.equals(desired.getMetadata().getName())) {
            return Future.failedFuture("Given name " + name + " incompatible with desired name "
                    + desired.getMetadata().getName());
        }

        Future<ReconcileResult<T>> fut = Future.future();
        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
            future -> {
                T current = operation().withName(name).get();
                if (desired != null) {
                    if (current == null) {
                        log.debug("{} {} does not exist, creating it", resourceKind, name);
                        internalCreate(name, desired).setHandler(future);
                    } else {
                        log.debug("{} {} already exists, patching it", resourceKind, name);
                        internalPatch(name, current, desired).setHandler(future);
                    }
                } else {
                    if (current != null) {
                        // Deletion is desired
                        log.debug("{} {} exist, deleting it", resourceKind, name);
                        internalDelete(name).setHandler(future);
                    } else {
                        log.debug("{} {} does not exist, noop", resourceKind, name);
                        future.complete(ReconcileResult.noop(null));
                    }
                }

            },
            false,
            fut
        );
        return fut;
    }

    /**
     * Asynchronously deletes the resource with the given {@code name}.
     * @param name The resource to be deleted.
     * @return A future which will be completed on the context thread
     * once the resource has been deleted.
     */
    private Future<ReconcileResult<T>> internalDelete(String name) {
        R resourceOp = operation().withName(name);
        Future<ReconcileResult<T>> watchForDeleteFuture = resourceSupport.selfClosingWatch(resourceOp,
            "observe deletion of " + resourceKind + " " + name,
            (action, resource) -> {
                if (action == Watcher.Action.DELETED) {
                    log.debug("{} {} has been deleted", resourceKind, name);
                    return ReconcileResult.deleted();
                } else {
                    return null;
                }
            });
        Future<Void> deleteFuture = resourceSupport.deleteAsync(resourceOp);
        return CompositeFuture.join(watchForDeleteFuture, deleteFuture).map(ReconcileResult.deleted());
    }

    /**
     * Patches the resource with the given name to match the given desired resource
     * and completes the given future accordingly.
     */
    protected Future<ReconcileResult<T>> internalPatch(String name, T current, T desired) {
        return internalPatch(name, current, desired, true);
    }

    protected Future<ReconcileResult<T>> internalPatch(String name, T current, T desired, boolean cascading) {
        try {
            T result = operation().withName(name).cascading(cascading).patch(desired);
            log.debug("{} {} has been patched", resourceKind, name);
            return Future.succeededFuture(wasChanged(current, result) ?
                    ReconcileResult.patched(result) : ReconcileResult.noop(result));
        } catch (Exception e) {
            log.debug("Caught exception while patching {} {}", resourceKind, name, e);
            return Future.failedFuture(e);
        }
    }

    private boolean wasChanged(T oldVersion, T newVersion) {
        if (oldVersion != null
                && oldVersion.getMetadata() != null
                && newVersion != null
                && newVersion.getMetadata() != null) {
            return !Objects.equals(oldVersion.getMetadata().getResourceVersion(), newVersion.getMetadata().getResourceVersion());
        } else {
            return true;
        }
    }

    /**
     * Creates a resource with the name with the given desired state
     * and completes the given future accordingly.
     */
    @SuppressWarnings("unchecked")
    protected Future<ReconcileResult<T>> internalCreate(String name, T desired) {
        try {
            ReconcileResult<T> result = ReconcileResult.created(operation().withName(name).create(desired));
            log.debug("{} {} has been created", resourceKind, name);
            return Future.succeededFuture(result);
        } catch (Exception e) {
            log.debug("Caught exception while creating {} {}", resourceKind, name, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Synchronously gets the resource with the given {@code name}.
     * @param name The name.
     * @return The resource, or null if it doesn't exist.
     */
    public T get(String name) {
        return operation().withName(name).get();
    }

    /**
     * Asynchronously gets the resource with the given {@code name}.
     * @param name The name.
     * @return A Future for the result.
     */
    public Future<T> getAsync(String name) {
        return resourceSupport.getAsync(operation().withName(name));
    }

    /**
     * Synchronously list the resources with the given {@code selector}.
     * @param selector The selector.
     * @return A list of matching resources.
     */
    public List<T> list(Labels selector) {
        return listInAnyNamespace(selector);
    }

    @SuppressWarnings("unchecked") // due to L extends KubernetesResourceList/*<T>*/
    protected List<T> listInAnyNamespace(Labels selector) {
        FilterWatchListMultiDeletable<T, L, Boolean, Watch, Watcher<T>> operation = operation().inAnyNamespace();

        if (selector != null) {
            Map<String, String> labels = selector.toMap();
            return operation.withLabels(labels)
                    .list()
                    .getItems();
        } else {
            return operation
                    .list()
                    .getItems();
        }
    }

}
