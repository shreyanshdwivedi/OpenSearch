/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

/**
 * Match-count totals computed during query execution, for front-ends that report
 * {@code hits.total}.
 *
 * <p>{@code value} is the sum of per-shard {@code rows_matched} — matches counted during
 * index/bitset evaluation, before parquet materialization and before any LIMIT. {@code exact}
 * is true only when every evaluated row group was counted: shards that skipped row groups via
 * the runtime dynamic filter (TopK min-competitive pruning — the analog of Lucene's WAND
 * early-termination) report a lower bound, mirroring vanilla's
 * {@code track_total_hits} relation semantics ({@code eq} vs {@code gte}).
 *
 * @param value the total match count (or lower bound when not exact)
 * @param exact whether {@code value} is the exact match count
 *
 * @opensearch.internal
 */
public record ExecutionTotals(long value, boolean exact) {

    /**
     * Implemented by result-row containers that carry {@link ExecutionTotals} alongside the
     * rows. Front-ends receive rows as {@code Iterable<Object[]>} through
     * {@link QueryPlanExecutor}; an {@code instanceof} check against this interface recovers
     * the totals without changing the executor API.
     */
    public interface Holder {
        /** Returns the totals for the execution that produced these rows, or null if unknown. */
        ExecutionTotals executionTotals();
    }
}
