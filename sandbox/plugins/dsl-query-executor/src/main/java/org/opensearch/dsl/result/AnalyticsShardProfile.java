/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.opensearch.analytics.exec.profile.QueryProfile;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.search.profile.PluggableShardProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine profile section rendered inside a shard entry of {@code profile.shards} as
 * {@code analytics_profile}: one entry per executed plan (hits, aggregation, count), each
 * tagged with its plan type. Attached to a {@link org.opensearch.search.profile.ProfileShardResult}
 * on the analytics path, so a calcite-served query carries this alongside empty Lucene profile
 * fields while a fallback query carries the classic fields instead.
 */
public final class AnalyticsShardProfile implements PluggableShardProfile {

    /** NamedWriteable name and the {@code profile.shards[]} field this renders under. */
    public static final String NAME = "analytics_profile";

    /** A profile tagged with the plan type that produced it. */
    public record PlanProfile(QueryPlans.Type planType, QueryProfile profile) {}

    private final List<PlanProfile> profiles;

    public AnalyticsShardProfile(List<PlanProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    public AnalyticsShardProfile(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<PlanProfile> read = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            QueryPlans.Type planType = QueryPlans.Type.valueOf(in.readString());
            read.add(new PlanProfile(planType, new QueryProfile(in)));
        }
        this.profiles = List.copyOf(read);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(profiles.size());
        for (PlanProfile entry : profiles) {
            out.writeString(entry.planType().name());
            entry.profile().writeTo(out);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startArray(NAME);
        for (PlanProfile entry : profiles) {
            builder.startObject();
            builder.field("plan_type", entry.planType().name());
            builder.field("profile");
            entry.profile().toXContent(builder, params);
            builder.endObject();
        }
        builder.endArray();
        return builder;
    }
}
