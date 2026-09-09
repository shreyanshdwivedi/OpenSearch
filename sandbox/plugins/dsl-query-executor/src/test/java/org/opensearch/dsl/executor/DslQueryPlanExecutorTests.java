/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.executor;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.analytics.QueryRequestContext;
import org.opensearch.analytics.exec.QueryPlanExecutor;
import org.opensearch.analytics.exec.profile.ProfiledResult;
import org.opensearch.analytics.exec.profile.QueryProfile;
import org.opensearch.core.action.ActionListener;
import org.opensearch.dsl.TestUtils;
import org.opensearch.dsl.result.ExecutionResult;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class DslQueryPlanExecutorTests extends OpenSearchTestCase {

    private LogicalTableScan scan;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        scan = TestUtils.createTestRelNode();
    }

    public void testExecuteDelegatesEachPlanToExecutor() {
        List<Object[]> expectedRows = List.<Object[]>of(new Object[] { "laptop", 1200 });

        DslQueryPlanExecutor executor = new DslQueryPlanExecutor((plan, ctx, listener) -> listener.onResponse(expectedRows));
        QueryPlans plans = new QueryPlans.Builder().add(new QueryPlans.QueryPlan(QueryPlans.Type.HITS, scan)).build();

        PlainActionFuture<List<ExecutionResult>> future = new PlainActionFuture<>();
        executor.execute(plans, future);
        List<ExecutionResult> results = future.actionGet();

        assertEquals(1, results.size());
        ExecutionResult result = results.get(0);
        assertSame(expectedRows, result.getRows());
        assertEquals(QueryPlans.Type.HITS, result.getType());
        assertNotNull(result.getPlan());
        assertSame(scan, result.getPlan().relNode());
        assertEquals(
            List.of(
                "name",
                "price",
                "brand",
                "rating",
                "created_date",
                "is_active",
                "timestamp",
                "location",
                "status",
                "binary_data",
                "event_time",
                "ip_address",
                "event_nanos",
                "scaled_price",
                "unsigned_counter",
                "tiny_val",
                "small_val",
                "float_val"
            ),
            result.getFieldNames()
        );
    }

    // TODO: add test with multiple plans (HITS + AGGREGATION) to verify iteration order
    // TODO: add test for executor failure

    public void testExecuteWithoutProfileLeavesProfileNull() {
        DslQueryPlanExecutor executor = new DslQueryPlanExecutor((plan, ctx, listener) -> listener.onResponse(List.of()));
        QueryPlans plans = new QueryPlans.Builder().add(new QueryPlans.QueryPlan(QueryPlans.Type.HITS, scan)).build();

        PlainActionFuture<List<ExecutionResult>> future = new PlainActionFuture<>();
        executor.execute(plans, false, future);

        assertNull(future.actionGet().get(0).getProfile());
    }

    public void testProfileExecutionCarriesEngineProfile() {
        List<Object[]> rows = List.<Object[]>of(new Object[] { "laptop" });
        QueryProfile profile = new QueryProfile("q1", List.of("plan line"), 3L, 7L, List.of());
        QueryPlanExecutor<RelNode, Iterable<Object[]>> engine = new QueryPlanExecutor<>() {
            @Override
            public void execute(RelNode plan, QueryRequestContext ctx, ActionListener<Iterable<Object[]>> listener) {
                fail("profile execution must route through executeWithProfile");
            }

            @Override
            public void executeWithProfile(RelNode plan, QueryRequestContext ctx, ActionListener<ProfiledResult> listener) {
                listener.onResponse(new ProfiledResult(rows, null, profile));
            }
        };
        DslQueryPlanExecutor executor = new DslQueryPlanExecutor(engine);
        QueryPlans plans = new QueryPlans.Builder().add(new QueryPlans.QueryPlan(QueryPlans.Type.HITS, scan)).build();

        PlainActionFuture<List<ExecutionResult>> future = new PlainActionFuture<>();
        executor.execute(plans, true, future);
        List<ExecutionResult> results = future.actionGet();

        assertEquals(1, results.size());
        assertSame(rows, results.get(0).getRows());
        assertSame(profile, results.get(0).getProfile());
    }

    public void testProfiledFailureAbortsChain() {
        QueryProfile profile = new QueryProfile("q1", List.of(), 0L, 0L, List.of());
        RuntimeException boom = new RuntimeException("engine failure");
        QueryPlanExecutor<RelNode, Iterable<Object[]>> engine = new QueryPlanExecutor<>() {
            @Override
            public void execute(RelNode plan, QueryRequestContext ctx, ActionListener<Iterable<Object[]>> listener) {
                fail("profile execution must route through executeWithProfile");
            }

            @Override
            public void executeWithProfile(RelNode plan, QueryRequestContext ctx, ActionListener<ProfiledResult> listener) {
                // executeWithProfile reports failures via onResponse with a populated profile
                listener.onResponse(new ProfiledResult(null, boom, profile));
            }
        };
        DslQueryPlanExecutor executor = new DslQueryPlanExecutor(engine);
        QueryPlans plans = new QueryPlans.Builder().add(new QueryPlans.QueryPlan(QueryPlans.Type.HITS, scan)).build();

        PlainActionFuture<List<ExecutionResult>> future = new PlainActionFuture<>();
        executor.execute(plans, true, future);

        RuntimeException thrown = expectThrows(RuntimeException.class, future::actionGet);
        assertEquals("engine failure", thrown.getMessage());
    }
}
