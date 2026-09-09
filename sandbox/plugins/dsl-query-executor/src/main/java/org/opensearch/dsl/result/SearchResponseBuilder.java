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
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.dsl.aggregation.AggregationRegistry;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.search.profile.NetworkTime;
import org.opensearch.search.profile.ProfileShardResult;
import org.opensearch.search.profile.SearchProfileShardResults;
import org.opensearch.search.profile.aggregation.AggregationProfileShardResult;
import org.opensearch.search.profile.fetch.FetchProfileShardResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a {@link SearchResponse} from execution results.
 * Handles conversion of flat execution results into OpenSearch response format.
 */
public class SearchResponseBuilder {

    private SearchResponseBuilder() {}

    /**
     * Builds a SearchResponse from execution results.
     *
     * @param results execution results from the query executor
     * @param request the original search request
     * @param registry aggregation registry for building aggregations
     * @param tookInMillis total execution time in milliseconds
     * @return a SearchResponse
     */
    public static SearchResponse build(
        List<ExecutionResult> results,
        SearchRequest request,
        AggregationRegistry registry,
        long tookInMillis
    ) throws ConversionException {

        CountTotals countTotals = extractCountTotals(results);
        SearchHits hits = buildHits(results, request, countTotals);
        InternalAggregations aggregations = buildAggregations(results, request, registry, countTotals);
        SearchProfileShardResults profileResults = buildProfileResults(results);

        SearchResponseSections sections = new SearchResponseSections(hits, aggregations, null, false, null, profileResults, 0);

        // TODO: shard counts, timed_out, and engine-side took require execution metadata from
        // the analytics plugin (returned alongside rows). Until then report a constant 1/1 —
        // the analytics path has no per-shard fan-out to report.
        return new SearchResponse(sections, null, 1, 1, 0, tookInMillis, ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
    }

    private static CountTotals extractCountTotals(List<ExecutionResult> results) {
        List<ExecutionResult> countResults = results.stream().filter(r -> r.getType() == QueryPlans.Type.COUNT).toList();
        return countResults.isEmpty() ? null : CountTotals.from(countResults);
    }

    /**
     * Builds the profile section when profiling was requested (results carry engine profiles),
     * else null so no {@code profile} key is rendered. The analytics engine has no per-shard
     * fan-out, so profiles from all executed plans (ordered HITS, AGGREGATION, COUNT for stable
     * output) attach to a single synthetic shard entry as {@code analytics_profile}; the classic
     * Lucene profile fields on that entry stay empty.
     */
    private static SearchProfileShardResults buildProfileResults(List<ExecutionResult> results) {
        List<AnalyticsShardProfile.PlanProfile> profiles = results.stream()
            .filter(r -> r.getProfile() != null)
            .sorted(Comparator.comparing(ExecutionResult::getType))
            .map(r -> new AnalyticsShardProfile.PlanProfile(r.getType(), r.getProfile()))
            .toList();
        if (profiles.isEmpty()) {
            return null;
        }
        ProfileShardResult shardResult = new ProfileShardResult(
            List.of(),
            new AggregationProfileShardResult(List.of()),
            new FetchProfileShardResult(List.of()),
            new NetworkTime(0, 0)
        );
        shardResult.setPluggableProfile(new AnalyticsShardProfile(profiles));
        return new SearchProfileShardResults(Map.of("[analytics][0]", shardResult));
    }

    /**
     * Builds the hits section. Hit documents are still stubbed (TODO below), but
     * {@code hits.total} renders classic {@code track_total_hits} semantics from the COUNT
     * plan's {@code COUNT(*)}: exact ({@code eq}) up to the threshold, a {@code gte} lower
     * bound past it, omitted when tracking is disabled.
     */
    private static SearchHits buildHits(List<ExecutionResult> results, SearchRequest request, CountTotals countTotals) {
        // TODO: Build hit documents from HITS results
        return new SearchHits(new SearchHit[0], resolveTotalHits(request, countTotals), Float.NaN);
    }

    private static TotalHits resolveTotalHits(SearchRequest request, CountTotals countTotals) {
        Integer trackUpTo = request.source() == null ? null : request.source().trackTotalHitsUpTo();
        int threshold = trackUpTo == null ? SearchContext.DEFAULT_TRACK_TOTAL_HITS_UP_TO : trackUpTo;
        if (threshold == SearchContext.TRACK_TOTAL_HITS_DISABLED) {
            return null; // track_total_hits: false — total omitted, like classic search
        }
        if (countTotals == null || countTotals.totalDocs() == null) {
            // No count ran (nothing requested it) — preserve the pre-count stub value.
            return new TotalHits(0, TotalHits.Relation.EQUAL_TO);
        }
        long count = countTotals.totalDocs();
        return count <= threshold
            ? new TotalHits(count, TotalHits.Relation.EQUAL_TO)
            : new TotalHits(threshold, TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO);
    }

    private static InternalAggregations buildAggregations(
        List<ExecutionResult> results,
        SearchRequest request,
        AggregationRegistry registry,
        CountTotals countTotals
    ) throws ConversionException {

        List<ExecutionResult> aggResults = results.stream()
            .filter(r -> r.getType() == QueryPlans.Type.AGGREGATION)
            .collect(Collectors.toList());

        if (aggResults.isEmpty() || request.source() == null || request.source().aggregations() == null) {
            return null;
        }

        AggregationResponseBuilder builder = new AggregationResponseBuilder(registry, aggResults, countTotals);
        return builder.build(new ArrayList<>(request.source().aggregations().getAggregatorFactories()));
    }
}
