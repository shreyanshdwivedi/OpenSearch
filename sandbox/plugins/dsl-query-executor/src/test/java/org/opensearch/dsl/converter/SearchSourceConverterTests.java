/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.converter;

import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.dsl.golden.CalciteTestInfra;
import org.opensearch.dsl.golden.GoldenFileLoader;
import org.opensearch.dsl.golden.GoldenTestCase;
import org.opensearch.search.SearchModule;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SearchSourceConverterTests extends OpenSearchTestCase {

    private SearchSourceConverter converter;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        SchemaPlus schema = CalciteSchema.createRootSchema(true).plus();
        schema.add("test-index", new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory typeFactory) {
                // Nullable fields — matches OpenSearchSchemaBuilder behavior
                return typeFactory.builder()
                    .add("name", typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.VARCHAR), true))
                    .add("price", typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.INTEGER), true))
                    .add("brand", typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.VARCHAR), true))
                    .add("rating", typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE), true))
                    .build();
            }
        });
        converter = new SearchSourceConverter(schema);
    }

    public void testConvertProducesHitsPlan() throws ConversionException {
        QueryPlans plans = converter.convert(new SearchSourceBuilder(), "test-index");

        assertEquals(1, plans.getAll().size());
        assertTrue(plans.has(QueryPlans.Type.HITS));

        // Even a default request carries fetch=size (10) so the limit pushes down to the engine.
        QueryPlans.QueryPlan plan = plans.get(QueryPlans.Type.HITS).get(0);
        assertTrue(plan.relNode() instanceof LogicalSort);
        LogicalSort sort = (LogicalSort) plan.relNode();
        assertNotNull(sort.fetch);
        assertTrue(sort.getInput() instanceof LogicalTableScan);
    }

    public void testConvertResolvesFieldNames() throws ConversionException {
        QueryPlans plans = converter.convert(new SearchSourceBuilder(), "test-index");

        QueryPlans.QueryPlan plan = plans.get(QueryPlans.Type.HITS).get(0);
        assertEquals(4, plan.relNode().getRowType().getFieldCount());
        assertEquals(List.of("name", "price", "brand", "rating"), plan.relNode().getRowType().getFieldNames());
    }

    public void testConvertThrowsForMissingIndex() {
        expectThrows(IllegalArgumentException.class, () -> converter.convert(new SearchSourceBuilder(), "nonexistent-index"));
    }

    public void testAggsWithSizeZeroProducesCountAndAggregationPlans() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(0).aggregation(new AvgAggregationBuilder("avg_price").field("price"));
        QueryPlans plans = converter.convert(source, "test-index");

        assertEquals(2, plans.getAll().size());
        assertFalse(plans.has(QueryPlans.Type.HITS));
        assertTrue(plans.has(QueryPlans.Type.COUNT));
        assertTrue(plans.has(QueryPlans.Type.AGGREGATION));
    }

    public void testAggsWithSizeGreaterThanZeroProducesBothPlans() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(10).aggregation(new AvgAggregationBuilder("avg_price").field("price"));
        QueryPlans plans = converter.convert(source, "test-index");

        assertEquals(2, plans.getAll().size());
        assertTrue(plans.has(QueryPlans.Type.HITS));
        assertTrue(plans.has(QueryPlans.Type.AGGREGATION));
        assertFalse(plans.has(QueryPlans.Type.COUNT));
    }

    public void testNoAggsProducesOnlyHitsPlan() throws ConversionException {
        QueryPlans plans = converter.convert(new SearchSourceBuilder(), "test-index");

        assertEquals(1, plans.getAll().size());
        assertTrue(plans.has(QueryPlans.Type.HITS));
        assertFalse(plans.has(QueryPlans.Type.AGGREGATION));
    }

    /** size>0 with explicit track_total_hits adds the reusable COUNT plan alongside HITS. */
    public void testExplicitTrackingAddsCountPlanForHitsQueries() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(10).trackTotalHits(true);
        QueryPlans plans = converter.convert(source, "test-index");

        assertEquals(2, plans.getAll().size());
        assertTrue(plans.has(QueryPlans.Type.HITS));
        assertTrue(plans.has(QueryPlans.Type.COUNT));
    }

    /** track_total_hits: false suppresses the COUNT plan even for size=0. */
    public void testTrackingDisabledSuppressesCountPlan() throws ConversionException {
        SearchSourceBuilder sizeZero = new SearchSourceBuilder().size(0).trackTotalHits(false);
        assertEquals(0, converter.convert(sizeZero, "test-index").getAll().size());

        SearchSourceBuilder hits = new SearchSourceBuilder().size(10).trackTotalHits(false);
        QueryPlans plans = converter.convert(hits, "test-index");
        assertEquals(1, plans.getAll().size());
        assertFalse(plans.has(QueryPlans.Type.COUNT));
    }

    /** Without explicit tracking, size>0 emits no COUNT plan (no extra query by default). */
    public void testNoTrackingNoCountPlanForHitsQueries() throws ConversionException {
        QueryPlans plans = converter.convert(new SearchSourceBuilder().size(10), "test-index");
        assertFalse(plans.has(QueryPlans.Type.COUNT));
    }

    /** size=0 is a count query: legacy still reports the exact match count in hits.total. */
    public void testSizeZeroProducesCountPlan() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(0);
        QueryPlans plans = converter.convert(source, "test-index");

        assertEquals(1, plans.getAll().size());
        assertFalse(plans.has(QueryPlans.Type.HITS));

        QueryPlans.QueryPlan countPlan = plans.get(QueryPlans.Type.COUNT).get(0);
        assertTrue(countPlan.relNode() instanceof LogicalAggregate);
        assertEquals(List.of(SearchSourceConverter.TOTAL_COUNT_FIELD), countPlan.relNode().getRowType().getFieldNames());
    }

    public void testAggPlanIncludesPostAggSort() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(0)
            .aggregation(
                new TermsAggregationBuilder("by_brand").field("brand")
                    .order(BucketOrder.key(true))
                    .subAggregation(new AvgAggregationBuilder("avg_price").field("price"))
            );
        QueryPlans plans = converter.convert(source, "test-index");

        assertTrue(plans.has(QueryPlans.Type.AGGREGATION));
        // Aggregation plan should be wrapped with LogicalSort for bucket order
        assertTrue(plans.get(QueryPlans.Type.AGGREGATION).get(0).relNode() instanceof LogicalSort);
    }

    public void testMetricOnlyAggPlanHasNoPostAggSort() throws ConversionException {
        SearchSourceBuilder source = new SearchSourceBuilder().size(0).aggregation(new AvgAggregationBuilder("avg_price").field("price"));
        QueryPlans plans = converter.convert(source, "test-index");

        assertTrue(plans.has(QueryPlans.Type.AGGREGATION));
        // Metric-only agg has no bucket orders, so no LogicalSort wrapper
        assertFalse(plans.get(QueryPlans.Type.AGGREGATION).get(0).relNode() instanceof LogicalSort);
    }

    // ---- Golden file driven RelNode generation tests ----

    /**
     * Auto-discovers all golden JSON files and validates that each inputDsl
     * produces the expected RelNode plan via SearchSourceConverter.convert().
     * Adding a new test case only requires adding a new JSON file — no new
     * Java method needed.
     */
    public void testGoldenFileRelNodeGeneration() throws Exception {
        URL goldenDir = getClass().getClassLoader().getResource("golden");
        assertNotNull("Golden file resource directory not found", goldenDir);

        List<Path> goldenFiles;
        try (var stream = Files.list(Path.of(goldenDir.toURI()))) {
            goldenFiles = stream.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
        }
        assertFalse("No golden files found", goldenFiles.isEmpty());

        List<String> failures = new ArrayList<>();
        for (Path file : goldenFiles) {
            String fileName = file.getFileName().toString();
            try {
                GoldenTestCase tc = GoldenFileLoader.load(fileName);
                CalciteTestInfra.InfraResult infra = CalciteTestInfra.buildFromMapping(tc.getIndexName(), tc.getIndexMapping());

                SearchSourceBuilder searchSource = parseSearchSource(tc.getInputDsl());
                SearchSourceConverter conv = new SearchSourceConverter(infra.schema());
                QueryPlans plans = conv.convert(searchSource, tc.getIndexName());

                QueryPlans.Type expectedType = QueryPlans.Type.valueOf(tc.getPlanType());
                List<QueryPlans.QueryPlan> matchingPlans = plans.get(expectedType);
                if (matchingPlans.isEmpty()) {
                    failures.add(fileName + ": No " + expectedType + " plan produced");
                    continue;
                }

                RelNode relNode = matchingPlans.get(0).relNode();
                String actualPlan = relNode.explain().trim();
                String expectedPlan = String.join("\n", tc.getExpectedRelNodePlan());

                if (!expectedPlan.equals(actualPlan)) {
                    failures.add(fileName + ": RelNode plan mismatch\n  Expected: " + expectedPlan + "\n  Actual:   " + actualPlan);
                }

                List<String> actualFields = relNode.getRowType().getFieldNames();
                if (!tc.getMockResultFieldNames().equals(actualFields)) {
                    failures.add(
                        fileName + ": Field names mismatch\n  Expected: " + tc.getMockResultFieldNames() + "\n  Actual:   " + actualFields
                    );
                }
            } catch (Exception e) {
                failures.add(fileName + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            fail("Golden file RelNode generation failures:\n" + String.join("\n", failures));
        }
    }

    public void testNanoTimestampTypeSystemReportsMaxPrecisionNine() {
        assertEquals(9, DslTypeSystems.NANO_TIMESTAMP.getMaxPrecision(SqlTypeName.TIMESTAMP));
    }

    private SearchSourceBuilder parseSearchSource(Map<String, Object> inputDsl) throws IOException {
        String json;
        try (var builder = JsonXContent.contentBuilder()) {
            builder.map(inputDsl);
            json = builder.toString();
        }
        NamedXContentRegistry registry = new NamedXContentRegistry(
            new SearchModule(Settings.EMPTY, Collections.emptyList()).getNamedXContents()
        );
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(registry, DeprecationHandler.IGNORE_DEPRECATIONS, json)) {
            return SearchSourceBuilder.fromXContent(parser);
        }
    }
}
