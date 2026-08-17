/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.lucene.search.TotalHits;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.analytics.exec.ExecutionTotals;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.dsl.golden.CalciteTestInfra;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.test.OpenSearchTestCase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HitsResponseBuilderTests extends OpenSearchTestCase {

    private static final Map<String, String> PRODUCTS_MAPPING = productsMapping();

    private static Map<String, String> productsMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("name", "VARCHAR");
        mapping.put("price", "INTEGER");
        mapping.put("brand", "VARCHAR");
        return mapping;
    }

    private static QueryPlans.QueryPlan hitsPlan(Map<String, String> mapping) {
        CalciteTestInfra.InfraResult infra = CalciteTestInfra.buildFromMapping("products", mapping);
        RelNode scan = LogicalTableScan.create(infra.cluster(), infra.table(), List.of());
        return new QueryPlans.QueryPlan(QueryPlans.Type.HITS, scan);
    }

    private static SearchRequest request(int size) {
        SearchRequest request = new SearchRequest("products");
        request.source(new SearchSourceBuilder().size(size));
        return request;
    }

    public void testBuildsHitsFromRows() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            List.of(new Object[] { "laptop", 999, "BrandA" }, new Object[] { "phone", 699, "BrandB" })
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(10));

        assertEquals(2, hits.getHits().length);
        SearchHit first = hits.getHits()[0];
        assertNull("engine schema exposes no _id yet", first.getId());
        assertTrue("no relevance scoring on the analytics path", Float.isNaN(first.getScore()));
        assertEquals(Map.of("name", "laptop", "price", 999, "brand", "BrandA"), first.getSourceAsMap());
        assertEquals(Map.of("name", "phone", "price", 699, "brand", "BrandB"), hits.getHits()[1].getSourceAsMap());
        assertTrue(Float.isNaN(hits.getMaxScore()));
    }

    /** A short page is a complete result set: total is exact. */
    public void testTotalIsExactWhenFewerRowsThanSize() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            List.<Object[]>of(new Object[] { "laptop", 999, "BrandA" })
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(10));

        assertEquals(1, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, hits.getTotalHits().relation());
    }

    /** A full page may have been cut off by the plan's LIMIT: total is a lower bound. */
    public void testTotalIsLowerBoundWhenPageIsFull() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            List.of(new Object[] { "laptop", 999, "BrandA" }, new Object[] { "phone", 699, "BrandB" })
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(2));

        assertEquals(2, hits.getHits().length);
        assertEquals(2, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, hits.getTotalHits().relation());
    }

    /** Defensive truncation when the engine returns more rows than size (e.g. missing LIMIT). */
    public void testTruncatesRowsBeyondSize() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            List.of(
                new Object[] { "laptop", 999, "BrandA" },
                new Object[] { "phone", 699, "BrandB" },
                new Object[] { "tablet", 499, "BrandC" }
            )
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(2));

        assertEquals(2, hits.getHits().length);
        assertEquals("laptop", hits.getHits()[0].getSourceAsMap().get("name"));
        assertEquals(3, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, hits.getTotalHits().relation());
    }

    /** No HITS result and no COUNT result (older plans): empty hits with an unknown ("at least 0") total. */
    public void testNoHitsResultYieldsEmptyHits() throws Exception {
        SearchHits hits = HitsResponseBuilder.build(List.of(), request(0));

        assertEquals(0, hits.getHits().length);
        assertEquals(0, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, hits.getTotalHits().relation());
        assertTrue(Float.isNaN(hits.getMaxScore()));
    }

    /** size=0 with a COUNT result: the exact match count is reported like legacy. */
    public void testCountResultYieldsExactTotal() throws Exception {
        ExecutionResult countResult = new ExecutionResult(countPlan(), List.<Object[]>of(new Object[] { 42L }));

        SearchHits hits = HitsResponseBuilder.build(List.of(countResult), request(0));

        assertEquals(0, hits.getHits().length);
        assertEquals(42L, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, hits.getTotalHits().relation());
        assertTrue(Float.isNaN(hits.getMaxScore()));
    }

    /** A COUNT result that is not a single numeric cell means the plan contract broke — throw. */
    public void testMalformedCountResultThrows() {
        ExecutionResult noRows = new ExecutionResult(countPlan(), List.of());
        expectThrows(ConversionException.class, () -> HitsResponseBuilder.build(List.of(noRows), request(0)));

        ExecutionResult nonNumeric = new ExecutionResult(countPlan(), List.<Object[]>of(new Object[] { "x" }));
        expectThrows(ConversionException.class, () -> HitsResponseBuilder.build(List.of(nonNumeric), request(0)));
    }

    private static QueryPlans.QueryPlan countPlan() {
        CalciteTestInfra.InfraResult infra = CalciteTestInfra.buildFromMapping("products", PRODUCTS_MAPPING);
        RelNode scan = LogicalTableScan.create(infra.cluster(), infra.table(), List.of());
        return new QueryPlans.QueryPlan(QueryPlans.Type.COUNT, scan);
    }

    /** AGGREGATION results must not be mistaken for hit rows. */
    public void testIgnoresAggregationResults() throws Exception {
        CalciteTestInfra.InfraResult infra = CalciteTestInfra.buildFromMapping("products", PRODUCTS_MAPPING);
        RelNode scan = LogicalTableScan.create(infra.cluster(), infra.table(), List.of());
        ExecutionResult aggResult = new ExecutionResult(
            new QueryPlans.QueryPlan(QueryPlans.Type.AGGREGATION, scan, null),
            List.<Object[]>of(new Object[] { "BrandA", 3, "x" })
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(aggResult), request(10));

        assertEquals(0, hits.getHits().length);
    }

    /** Object fields arrive flattened as dotted columns and must be re-nested in _source. */
    public void testDottedColumnsAreReNested() throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("name", "VARCHAR");
        mapping.put("city.location.lat", "DOUBLE");
        mapping.put("city.location.lon", "DOUBLE");
        mapping.put("city.name", "VARCHAR");

        ExecutionResult result = new ExecutionResult(
            hitsPlan(mapping),
            List.<Object[]>of(new Object[] { "store1", 47.6, -122.3, "Seattle" })
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(10));

        Map<String, Object> source = hits.getHits()[0].getSourceAsMap();
        assertEquals("store1", source.get("name"));
        Map<String, Object> city = Map.of("location", Map.of("lat", 47.6, "lon", -122.3), "name", "Seattle");
        assertEquals(city, source.get("city"));
    }

    /** Null cells are omitted: absent vs explicit null is indistinguishable after the columnar round trip. */
    public void testNullCellsAreOmitted() throws Exception {
        ExecutionResult result = new ExecutionResult(hitsPlan(PRODUCTS_MAPPING), List.<Object[]>of(new Object[] { "laptop", null, null }));

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(10));

        assertEquals(Map.of("name", "laptop"), hits.getHits()[0].getSourceAsMap());
    }

    /** Engine-computed totals (track_total_hits gate) are used for sorted requests. */
    public void testEngineTotalsUsedForSortedRequests() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            new RowsWithTotalsForTest(List.<Object[]>of(new Object[] { "laptop", 999, "BrandA" }), new ExecutionTotals(42_000L, true))
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), sortedRequest(10));

        assertEquals(1, hits.getHits().length);
        assertEquals(42_000L, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, hits.getTotalHits().relation());
    }

    /** Inexact engine totals (dynamic-filter pruning) surface as a gte lower bound. */
    public void testInexactEngineTotalsReportGte() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            new RowsWithTotalsForTest(List.<Object[]>of(new Object[] { "laptop", 999, "BrandA" }), new ExecutionTotals(42_000L, false))
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), sortedRequest(10));

        assertEquals(42_000L, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, hits.getTotalHits().relation());
    }

    /** Unsorted requests ignore engine totals: their fragments can stop early and undercount. */
    public void testEngineTotalsIgnoredForUnsortedRequests() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            new RowsWithTotalsForTest(List.<Object[]>of(new Object[] { "laptop", 999, "BrandA" }), new ExecutionTotals(42_000L, true))
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), request(10));

        // Falls back to page inference: 1 row < size 10 -> exact 1.
        assertEquals(1L, hits.getTotalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, hits.getTotalHits().relation());
    }

    /** A totals value below the returned row count is nonsense - fall back to inference. */
    public void testImplausibleEngineTotalsIgnored() throws Exception {
        ExecutionResult result = new ExecutionResult(
            hitsPlan(PRODUCTS_MAPPING),
            new RowsWithTotalsForTest(
                List.of(new Object[] { "laptop", 999, "BrandA" }, new Object[] { "phone", 699, "BrandB" }),
                new ExecutionTotals(1L, true)
            )
        );

        SearchHits hits = HitsResponseBuilder.build(List.of(result), sortedRequest(10));

        assertEquals(2L, hits.getTotalHits().value());
    }

    private static SearchRequest sortedRequest(int size) {
        SearchRequest request = new SearchRequest("products");
        request.source(new SearchSourceBuilder().size(size).sort("price", SortOrder.DESC));
        return request;
    }

    /** Test stand-in for the engine's rows-with-totals wrapper. */
    private static final class RowsWithTotalsForTest implements Iterable<Object[]>, ExecutionTotals.Holder {
        private final List<Object[]> rows;
        private final ExecutionTotals totals;

        RowsWithTotalsForTest(List<Object[]> rows, ExecutionTotals totals) {
            this.rows = rows;
            this.totals = totals;
        }

        @Override
        public java.util.Iterator<Object[]> iterator() {
            return rows.iterator();
        }

        @Override
        public ExecutionTotals executionTotals() {
            return totals;
        }
    }

    public void testRowCellCountMismatchThrows() {
        ExecutionResult result = new ExecutionResult(hitsPlan(PRODUCTS_MAPPING), List.<Object[]>of(new Object[] { "laptop", 999 }));

        expectThrows(ConversionException.class, () -> HitsResponseBuilder.build(List.of(result), request(10)));
    }

    public void testDottedColumnConflictingWithScalarThrows() {
        expectThrows(
            ConversionException.class,
            () -> HitsResponseBuilder.buildSourceMap(List.of("name", "name.first"), new Object[] { "laptop", "x" })
        );
    }
}
