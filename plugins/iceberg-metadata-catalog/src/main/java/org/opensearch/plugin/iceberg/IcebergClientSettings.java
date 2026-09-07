/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.iceberg;

import org.opensearch.common.settings.SecureSetting;
import org.opensearch.core.common.settings.SecureString;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * Keystore-backed credentials for the Iceberg / S3 Tables interaction, following the
 * same named-client pattern as repository-s3 (s3.client dot name) but under the
 * iceberg.client prefix to avoid clashing with repository-s3's settings.
 *
 * Two client names are used by the plugin:
 * <ul>
 *   <li>{@code default} — customer credentials for the S3 Tables REST catalog and warehouse writes</li>
 *   <li>{@code source} — credentials for reading the remote segment store (falls back to {@code default})</li>
 * </ul>
 *
 * Credentials must be read while the node keystore is open (plugin construction or
 * a {@code _nodes/reload_secure_settings} reload) — hence the immutable {@link Creds}
 * snapshot instead of reading settings lazily.
 */
public final class IcebergClientSettings {

    private static final String PREFIX = "iceberg.client.";

    public static final String DEFAULT_CLIENT = "default";
    public static final String SOURCE_CLIENT = "source";

    /** {@code iceberg.client.<name>.access_key} */
    public static final Setting.AffixSetting<SecureString> ACCESS_KEY_SETTING = Setting.affixKeySetting(
        PREFIX,
        "access_key",
        key -> SecureSetting.secureString(key, null)
    );

    /** {@code iceberg.client.<name>.secret_key} */
    public static final Setting.AffixSetting<SecureString> SECRET_KEY_SETTING = Setting.affixKeySetting(
        PREFIX,
        "secret_key",
        key -> SecureSetting.secureString(key, null)
    );

    /** {@code iceberg.client.<name>.session_token} */
    public static final Setting.AffixSetting<SecureString> SESSION_TOKEN_SETTING = Setting.affixKeySetting(
        PREFIX,
        "session_token",
        key -> SecureSetting.secureString(key, null)
    );

    private IcebergClientSettings() {}

    /** Immutable credentials snapshot. {@link #EMPTY} means "not configured". */
    public static final class Creds {
        public static final Creds EMPTY = new Creds(null, null, null);

        private final String accessKey;
        private final String secretKey;
        private final String sessionToken;

        private Creds(String accessKey, String secretKey, String sessionToken) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.sessionToken = sessionToken;
        }

        public boolean isConfigured() {
            return accessKey != null && accessKey.isEmpty() == false && secretKey != null && secretKey.isEmpty() == false;
        }

        public String accessKey() {
            return accessKey;
        }

        public String secretKey() {
            return secretKey;
        }

        /** May be null for long-term keys. */
        public String sessionToken() {
            return sessionToken;
        }

        public AwsCredentials toAwsCredentials() {
            if (sessionToken != null && sessionToken.isEmpty() == false) {
                return AwsSessionCredentials.create(accessKey, secretKey, sessionToken);
            }
            return AwsBasicCredentials.create(accessKey, secretKey);
        }
    }

    /**
     * Read the named client's credentials from settings. Must be called while the
     * keystore is readable (node construction or secure-settings reload).
     */
    public static Creds loadCreds(Settings settings, String clientName) {
        try (
            SecureString accessKey = ACCESS_KEY_SETTING.getConcreteSettingForNamespace(clientName).get(settings);
            SecureString secretKey = SECRET_KEY_SETTING.getConcreteSettingForNamespace(clientName).get(settings);
            SecureString sessionToken = SESSION_TOKEN_SETTING.getConcreteSettingForNamespace(clientName).get(settings)
        ) {
            if (accessKey == null || accessKey.length() == 0 || secretKey == null || secretKey.length() == 0) {
                return Creds.EMPTY;
            }
            return new Creds(
                accessKey.toString(),
                secretKey.toString(),
                (sessionToken == null || sessionToken.length() == 0) ? null : sessionToken.toString()
            );
        }
    }

    /** Load the source-side creds, falling back to the default client when unset. */
    public static Creds loadSourceCreds(Settings settings) {
        Creds source = loadCreds(settings, SOURCE_CLIENT);
        return source.isConfigured() ? source : loadCreds(settings, DEFAULT_CLIENT);
    }
}
