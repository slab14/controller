/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.databroker.actors.dds;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.CheckedFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.opendaylight.controller.cluster.access.concepts.TransactionIdentifier;
import org.opendaylight.mdsal.common.api.ReadFailedException;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreThreePhaseCommitCohort;
import org.opendaylight.yangtools.concepts.Identifiable;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side view of a free-standing transaction.
 *
 * <p>
 * This interface is used by the world outside of the actor system and in the actor system it is manifested via
 * its client actor. That requires some state transfer with {@link DistributedDataStoreClientBehavior}. In order to
 * reduce request latency, all messages are carbon-copied (and enqueued first) to the client actor.
 *
 * <p>
 * It is internally composed of multiple {@link RemoteProxyTransaction}s, each responsible for a component shard.
 *
 * <p>
 * Implementation is quite a bit complex, and involves cooperation with {@link AbstractClientHistory} for tracking
 * gaps in transaction identifiers seen by backends.
 *
 * <p>
 * These gaps need to be accounted for in the transaction setup message sent to a particular backend, so it can verify
 * that the requested transaction is in-sequence. This is critical in ensuring that transactions (which are independent
 * entities from message queueing perspective) do not get reodered -- thus allowing multiple in-flight transactions.
 *
 * <p>
 * Alternative would be to force visibility by sending an abort request to all potential backends, but that would mean
 * that even empty transactions increase load on all shards -- which would be a scalability issue.
 *
 * <p>
 * Yet another alternative would be to introduce inter-transaction dependencies to the queueing layer in client actor,
 * but that would require additional indirection and complexity.
 *
 * @author Robert Varga
 */
@Beta
public final class ClientTransaction extends LocalAbortable implements Identifiable<TransactionIdentifier> {
    private static final Logger LOG = LoggerFactory.getLogger(ClientTransaction.class);
    private static final AtomicIntegerFieldUpdater<ClientTransaction> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ClientTransaction.class, "state");
    private static final int OPEN_STATE = 0;
    private static final int CLOSED_STATE = 1;

    private final Map<Long, AbstractProxyTransaction> proxies = new ConcurrentHashMap<>();
    private final TransactionIdentifier transactionId;
    private final AbstractClientHistory parent;

    private volatile int state = OPEN_STATE;

    ClientTransaction(final AbstractClientHistory parent, final TransactionIdentifier transactionId) {
        this.transactionId = Preconditions.checkNotNull(transactionId);
        this.parent = Preconditions.checkNotNull(parent);
    }

    private void checkNotClosed() {
        Preconditions.checkState(state == OPEN_STATE, "Transaction %s is closed", transactionId);
    }

    private AbstractProxyTransaction createProxy(final Long shard) {
        return parent.createTransactionProxy(transactionId, shard);
    }

    private AbstractProxyTransaction ensureProxy(final YangInstanceIdentifier path) {
        checkNotClosed();

        final Long shard = parent.resolveShardForPath(path);
        return proxies.computeIfAbsent(shard, this::createProxy);
    }

    @Override
    public TransactionIdentifier getIdentifier() {
        return transactionId;
    }

    public CheckedFuture<Boolean, ReadFailedException> exists(final YangInstanceIdentifier path) {
        return ensureProxy(path).exists(path);
    }

    public CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> read(final YangInstanceIdentifier path) {
        return ensureProxy(path).read(path);
    }

    public void delete(final YangInstanceIdentifier path) {
        ensureProxy(path).delete(path);
    }

    public void merge(final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        ensureProxy(path).merge(path, data);
    }

    public void write(final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        ensureProxy(path).write(path, data);
    }

    private boolean ensureClosed() {
        final int local = state;
        if (local == CLOSED_STATE) {
            return false;
        }

        final boolean success = STATE_UPDATER.compareAndSet(this, OPEN_STATE, CLOSED_STATE);
        Preconditions.checkState(success, "Transaction %s raced during close", this);
        return true;
    }

    public DOMStoreThreePhaseCommitCohort ready() {
        Preconditions.checkState(ensureClosed(), "Attempted to submit a closed transaction %s", this);

        for (AbstractProxyTransaction p : proxies.values()) {
            p.seal();
        }

        final AbstractTransactionCommitCohort cohort;
        switch (proxies.size()) {
            case 0:
                cohort = new EmptyTransactionCommitCohort(parent, transactionId);
                break;
            case 1:
                cohort = new DirectTransactionCommitCohort(parent, transactionId,
                    Iterables.getOnlyElement(proxies.values()));
                break;
            default:
                cohort = new ClientTransactionCommitCohort(parent, transactionId, proxies.values());
                break;
        }

        return parent.onTransactionReady(transactionId, cohort);
    }

    /**
     * Release all state associated with this transaction.
     */
    public void abort() {
        if (commonAbort()) {
            parent.onTransactionAbort(transactionId);
        }
    }

    private boolean commonAbort() {
        if (!ensureClosed()) {
            return false;
        }

        for (AbstractProxyTransaction proxy : proxies.values()) {
            proxy.abort();
        }
        proxies.clear();
        return true;
    }

    @Override
    void localAbort(final Throwable cause) {
        LOG.debug("Local abort of transaction {}", getIdentifier(), cause);
        commonAbort();
    }

    Map<Long, AbstractProxyTransaction> getProxies() {
        return proxies;
    }
}
