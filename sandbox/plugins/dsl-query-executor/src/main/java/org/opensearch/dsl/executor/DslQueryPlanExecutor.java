/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.executor;

import org.apache.calcite.rel.RelNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.analytics.exec.QueryPlanExecutor;
import org.opensearch.core.action.ActionListener;
import org.opensearch.dsl.result.ExecutionResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Iterates over {@link QueryPlans}, delegates each RelNode to the analytics engine's
 * {@link QueryPlanExecutor}, and collects results.
 */
public class DslQueryPlanExecutor {

    private static final Logger logger = LogManager.getLogger(DslQueryPlanExecutor.class);

    private final QueryPlanExecutor<RelNode, Iterable<Object[]>> executor;

    /**
     * Creates an executor backed by the given analytics engine plan executor.
     *
     * @param executor analytics engine executor that runs individual RelNode plans
     */
    public DslQueryPlanExecutor(QueryPlanExecutor<RelNode, Iterable<Object[]>> executor) {
        this.executor = executor;
    }

    // TODO: add per-plan error handling so a failure in one plan
    // doesn't prevent returning partial results from other plans (e.g. HITS)
    /**
     * Executes all plans without profiling. Equivalent to {@link #execute(QueryPlans, boolean, ActionListener)}
     * with {@code profile = false}.
     */
    public void execute(QueryPlans plans, ActionListener<List<ExecutionResult>> listener) {
        execute(plans, false, listener);
    }

    /**
     * Executes all plans sequentially and delivers results, in plan order, to the listener.
     *
     * <p>Plans run one-at-a-time: plan {@code N+1} is dispatched only after plan {@code N}
     * completes successfully. The first failure aborts the chain — the listener fires
     * {@code onFailure} with that error and remaining plans do not run.
     *
     * <p>When {@code profile} is true, each plan runs via {@link QueryPlanExecutor#executeWithProfile}
     * and its {@link ExecutionResult} carries the engine's profile.
     *
     * @param plans    the query plans to execute
     * @param profile  whether to capture the engine's execution profile per plan
     * @param listener receives the ordered list of results on success, or the first failure
     */
    public void execute(QueryPlans plans, boolean profile, ActionListener<List<ExecutionResult>> listener) {
        List<QueryPlans.QueryPlan> queryPlans = plans.getAll();
        List<ExecutionResult> results = new ArrayList<>(queryPlans.size());
        executeNext(queryPlans, 0, profile, results, listener);
    }

    private void executeNext(
        List<QueryPlans.QueryPlan> queryPlans,
        int index,
        boolean profile,
        List<ExecutionResult> results,
        ActionListener<List<ExecutionResult>> outer
    ) {
        if (index >= queryPlans.size()) {
            outer.onResponse(results);
            return;
        }
        QueryPlans.QueryPlan plan = queryPlans.get(index);
        RelNode relNode = plan.relNode();
        logPlan(relNode);
        // TODO: context param is null, may carry execution hints
        if (profile) {
            // executeWithProfile delivers failures via onResponse with a populated profile;
            // match the non-profile path's semantics: first failure aborts the chain.
            executor.executeWithProfile(relNode, null, ActionListener.wrap(profiled -> {
                if (profiled.isSuccess() == false) {
                    outer.onFailure(asException(profiled.failure()));
                    return;
                }
                logRows(profiled.rows());
                results.add(new ExecutionResult(plan, profiled.rows(), profiled.profile()));
                executeNext(queryPlans, index + 1, profile, results, outer);
            }, outer::onFailure));
            return;
        }
        executor.execute(relNode, null, ActionListener.wrap(rows -> {
            logRows(rows);
            results.add(new ExecutionResult(plan, rows));
            executeNext(queryPlans, index + 1, profile, results, outer);
        }, outer::onFailure));
    }

    // executeWithProfile signals failure through ProfiledResult.failure() rather than the
    // listener's onFailure. Errors (OOM etc.) must propagate, not be delivered as a response.
    private static Exception asException(Throwable t) {
        if (t instanceof Error error) {
            throw error;
        }
        return t instanceof Exception ? (Exception) t : new RuntimeException(t);
    }

    private static void logRows(Iterable<Object[]> rows) {
        if (logger.isDebugEnabled() == false) return;
        List<Object[]> list = (rows instanceof List) ? (List<Object[]>) rows : null;
        int count = list != null ? list.size() : -1;
        logger.debug("Query result rowCount={}", count);
        if (list != null) {
            int preview = Math.min(20, list.size());
            for (int i = 0; i < preview; i++) {
                logger.debug("row[{}]={}", i, Arrays.toString(list.get(i)));
            }
            if (list.size() > preview) {
                logger.debug("... ({} more rows)", list.size() - preview);
            }
        }
    }

    // invalidateMetadataQuery() and THREAD_PROVIDERS are only needed for explain() output
    private void logPlan(RelNode relNode) {
        if (logger.isDebugEnabled()) {
            org.apache.calcite.rel.metadata.RelMetadataQueryBase.THREAD_PROVIDERS.set(
                org.apache.calcite.rel.metadata.JaninoRelMetadataProvider.of(
                    java.util.Objects.requireNonNull(relNode.getCluster().getMetadataProvider())
                )
            );
            relNode.getCluster().invalidateMetadataQuery();
            logger.debug("Executing RelNode:\n{}", relNode.explain());
        }
    }
}
