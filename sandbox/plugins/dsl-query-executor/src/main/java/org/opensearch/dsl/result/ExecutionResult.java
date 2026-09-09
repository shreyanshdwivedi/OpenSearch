/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.opensearch.analytics.exec.profile.QueryProfile;
import org.opensearch.dsl.executor.QueryPlans;

import java.util.List;
import java.util.Objects;

/**
 * Result of executing a single {@link QueryPlans.QueryPlan}: the original plan
 * paired with the rows returned by the analytics engine.
 */
public final class ExecutionResult {

    private final QueryPlans.QueryPlan plan;
    private final Iterable<Object[]> rows;
    private final QueryProfile profile;

    /**
     * Creates a result for the given plan and rows.
     *
     * @param plan the plan that produced this result
     * @param rows result rows from the executor
     */
    public ExecutionResult(QueryPlans.QueryPlan plan, Iterable<Object[]> rows) {
        this(plan, rows, null);
    }

    /**
     * Creates a result carrying the engine's execution profile.
     *
     * @param plan the plan that produced this result
     * @param rows result rows from the executor
     * @param profile engine profile captured for this plan, or null when profiling was not requested
     */
    public ExecutionResult(QueryPlans.QueryPlan plan, Iterable<Object[]> rows, QueryProfile profile) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.rows = Objects.requireNonNull(rows, "rows must not be null");
        this.profile = profile;
    }

    /** Returns the plan that produced this result. */
    public QueryPlans.QueryPlan getPlan() {
        return plan;
    }

    /** Returns the plan type (HITS or AGGREGATION). */
    public QueryPlans.Type getType() {
        return plan.type();
    }

    /** Returns the result rows from the executor. */
    public Iterable<Object[]> getRows() {
        return rows;
    }

    /** Returns the engine profile for this plan, or null when profiling was not requested. */
    public QueryProfile getProfile() {
        return profile;
    }

    /** Column names derived from the plan's RelNode row type. */
    public List<String> getFieldNames() {
        return plan.relNode().getRowType().getFieldNames();
    }
}
