/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.exec;

import java.util.Iterator;
import java.util.Objects;

/**
 * Result rows paired with {@link ExecutionTotals}. Front-ends receive rows as
 * {@code Iterable<Object[]>} through the executor API; the totals ride along via the
 * {@link ExecutionTotals.Holder} interface so no executor signature changes.
 *
 * @opensearch.internal
 */
final class RowsWithTotals implements Iterable<Object[]>, ExecutionTotals.Holder {

    private final Iterable<Object[]> rows;
    private final ExecutionTotals totals;

    RowsWithTotals(Iterable<Object[]> rows, ExecutionTotals totals) {
        this.rows = Objects.requireNonNull(rows, "rows must not be null");
        this.totals = Objects.requireNonNull(totals, "totals must not be null");
    }

    @Override
    public Iterator<Object[]> iterator() {
        return rows.iterator();
    }

    @Override
    public ExecutionTotals executionTotals() {
        return totals;
    }
}
