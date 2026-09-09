/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.profile;

import org.opensearch.common.annotation.PublicApi;
import org.opensearch.core.common.io.stream.NamedWriteable;
import org.opensearch.core.xcontent.ToXContentFragment;

/**
 * An engine-specific profile section attached to a {@link ProfileShardResult}, rendered
 * inside that shard's entry in the {@code profile.shards} array alongside the classic Lucene
 * profile fields.
 *
 * <p>Query engines outside core (for example the analytics/Calcite engine) implement this to
 * contribute their own profile shape without core depending on their types. It is carried as a
 * {@link NamedWriteable} so a node deserializing a {@link org.opensearch.action.search.SearchResponse}
 * resolves the concrete implementation from the registry, and renders itself via
 * {@link ToXContentFragment} under whatever field name the implementation chooses.
 *
 * @opensearch.api
 */
@PublicApi(since = "3.9.0")
public interface PluggableShardProfile extends NamedWriteable, ToXContentFragment {}
