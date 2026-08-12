/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.apache.lucene.search.TotalHits;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.SearchService;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a HITS execution result into {@link SearchHits}.
 *
 * <p>Each row is one document: the plan row type's column names paired with the row's cells
 * are re-assembled into a {@code _source} map (dotted column names re-nested into objects,
 * null cells omitted — matching how object fields were flattened into schema columns).
 *
 * <p>Legacy-compat choices, constrained by what the analytics engine exposes today:
 * <ul>
 *   <li>{@code _id} is omitted (null): the engine schema exposes only mapped source fields,
 *       no metadata columns. TODO: populate once the engine can project the stored
 *       {@code _id} (and {@code _source}) parquet columns for HITS plans.</li>
 *   <li>{@code _score}/{@code max_score} are {@link Float#NaN} (rendered {@code null}): the
 *       engine has no relevance scoring, matching legacy's non-scored (field-sorted) responses.</li>
 *   <li>{@code hits.total}: when a {@link QueryPlans.Type#COUNT} result is present (size=0
 *       requests), the exact match count is reported ({@code eq}) like legacy. Otherwise the
 *       count is inferred from the returned page, using the same threshold semantics legacy
 *       exposes via {@code track_total_hits}: a full page means "at least this many"
 *       ({@code gte}) and a short page is exact ({@code eq}).</li>
 * </ul>
 */
public final class HitsResponseBuilder {

    private HitsResponseBuilder() {}

    /**
     * Builds {@link SearchHits} from the HITS execution result, if any.
     *
     * @param results all execution results; at most one is a {@link QueryPlans.Type#HITS} result
     *        and at most one a {@link QueryPlans.Type#COUNT} result
     * @param request the original search request ({@code size} drives truncation and the total relation)
     * @return the search hits section of the response
     * @throws ConversionException if a row cannot be assembled into a source document
     */
    public static SearchHits build(List<ExecutionResult> results, SearchRequest request) throws ConversionException {
        ExecutionResult hitsResult = null;
        ExecutionResult countResult = null;
        for (ExecutionResult result : results) {
            if (result.getType() == QueryPlans.Type.HITS) {
                hitsResult = result;
            } else if (result.getType() == QueryPlans.Type.COUNT) {
                countResult = result;
            }
        }

        if (hitsResult == null) {
            // size=0 request: no hits were fetched. The COUNT plan supplies the exact match
            // count like legacy; without one (results not produced by the converter, e.g. in
            // tests) fall back to the honest lower bound.
            TotalHits total = countResult != null
                ? new TotalHits(extractCount(countResult), TotalHits.Relation.EQUAL_TO)
                : new TotalHits(0, TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO);
            return new SearchHits(new SearchHit[0], total, Float.NaN);
        }

        int size = resolveSize(request);
        List<String> fieldNames = hitsResult.getFieldNames();
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : hitsResult.getRows()) {
            rows.add(row);
        }

        // The plan carries fetch=size, but truncate defensively in case the engine returned more.
        int hitCount = Math.min(rows.size(), size);
        SearchHit[] hits = new SearchHit[hitCount];
        for (int i = 0; i < hitCount; i++) {
            hits[i] = buildHit(i, fieldNames, rows.get(i));
        }

        // eq/gte semantics: see class javadoc.
        TotalHits.Relation relation = rows.size() < size ? TotalHits.Relation.EQUAL_TO : TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
        return new SearchHits(hits, new TotalHits(rows.size(), relation), Float.NaN);
    }

    private static int resolveSize(SearchRequest request) {
        SearchSourceBuilder source = request.source();
        return source != null && source.size() != -1 ? source.size() : SearchService.DEFAULT_SIZE;
    }

    /**
     * Extracts the match count from a COUNT result: a global {@code COUNT(*)} yields exactly
     * one row with one cell. Anything else means the plan/result contract broke upstream.
     */
    private static long extractCount(ExecutionResult countResult) throws ConversionException {
        Object[] row = null;
        for (Object[] r : countResult.getRows()) {
            if (row != null) {
                throw new ConversionException("COUNT result returned more than one row");
            }
            row = r;
        }
        if (row == null || row.length != 1) {
            throw new ConversionException(
                "COUNT result must be a single row with a single cell, got " + (row == null ? "no rows" : row.length + " cells")
            );
        }
        if (!(row[0] instanceof Number number)) {
            throw new ConversionException(
                "COUNT result cell is not numeric: " + (row[0] == null ? "null" : row[0].getClass().getSimpleName())
            );
        }
        return number.longValue();
    }

    private static SearchHit buildHit(int docId, List<String> fieldNames, Object[] row) throws ConversionException {
        Map<String, Object> source = buildSourceMap(fieldNames, row);
        // id=null renders the hit without an _id field (see class javadoc).
        SearchHit hit = new SearchHit(docId, null, null, null);
        hit.score(Float.NaN);
        try (XContentBuilder builder = JsonXContent.contentBuilder()) {
            builder.map(source);
            hit.sourceRef(BytesReference.bytes(builder));
        } catch (IOException e) {
            throw new ConversionException("Failed to serialize hit _source", e);
        }
        return hit;
    }

    /**
     * Re-assembles a row into a source map. Dotted column names (the schema flattens object
     * fields into {@code parent.child} leaf columns) are re-nested into object maps so the
     * rendered {@code _source} matches the legacy document shape. Null cells are omitted —
     * after the columnar round trip an absent field and an explicit null are indistinguishable.
     */
    static Map<String, Object> buildSourceMap(List<String> fieldNames, Object[] row) throws ConversionException {
        if (fieldNames.size() != row.length) {
            throw new ConversionException(
                "HITS row has " + row.length + " cells but the plan row type declares " + fieldNames.size() + " columns"
            );
        }
        Map<String, Object> source = new LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) {
            if (row[i] == null) {
                continue;
            }
            insertNested(source, fieldNames.get(i), row[i]);
        }
        return source;
    }

    @SuppressWarnings("unchecked")
    private static void insertNested(Map<String, Object> root, String dottedName, Object value) throws ConversionException {
        String[] parts = dottedName.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (child == null) {
                Map<String, Object> next = new LinkedHashMap<>();
                current.put(parts[i], next);
                current = next;
            } else if (child instanceof Map) {
                current = (Map<String, Object>) child;
            } else {
                throw new ConversionException("Column '" + dottedName + "' conflicts with scalar column '" + parts[i] + "'");
            }
        }
        Object previous = current.put(parts[parts.length - 1], value);
        if (previous != null) {
            throw new ConversionException("Column '" + dottedName + "' conflicts with an object column of the same path");
        }
    }
}
