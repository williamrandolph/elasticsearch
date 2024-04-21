/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.integration;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.SystemIndexThreadPoolUtils;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackSettings;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.elasticsearch.test.SecuritySettingsSource.SECURITY_REQUEST_OPTIONS;
import static org.elasticsearch.test.rest.ESRestTestCase.entityAsMap;
import static org.hamcrest.Matchers.equalTo;

public class SecurityThreadPoolIT extends SecurityIntegTestCase {

    @Override
    protected boolean addMockHttpTransport() {
        return false; // need real http
    }

    /**
     * Security indices should be using {@link org.elasticsearch.indices.ExecutorNames#CRITICAL_SYSTEM_INDEX_THREAD_POOLS}
     * so we should be fine to block the default system index thread pools
     */
    private Set<String> threadPoolsToBlock() {
        Set<String> threadPoolsToBlock = new HashSet<>();
        threadPoolsToBlock.add(ThreadPool.Names.GET);
        threadPoolsToBlock.add(ThreadPool.Names.WRITE);
        threadPoolsToBlock.add(ThreadPool.Names.SEARCH);
        // TODO[wrb]: We can't block these yet because of https://github.com/elastic/elasticsearch/issues/106982
        // (critical thread pools not used for bulk writes)
        // threadPoolsToBlock.add(ExecutorNames.DEFAULT_SYSTEM_INDEX_THREAD_POOLS.threadPoolForWrite());
        // threadPoolsToBlock.add(ExecutorNames.DEFAULT_SYSTEM_INDEX_THREAD_POOLS.threadPoolForSearch());
        // threadPoolsToBlock.add(ExecutorNames.DEFAULT_SYSTEM_INDEX_THREAD_POOLS.threadPoolForGet());
        return threadPoolsToBlock;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(XPackSettings.TOKEN_SERVICE_ENABLED_SETTING.getKey(), true)
            .build();
    }

    // test for main
    public void testMainSecurityIndex() throws Exception {
        assertSecurityIndexActive();
        SystemIndexThreadPoolUtils.runWithBlockedThreadPools(threadPoolsToBlock(), internalCluster(), () -> {
            try {
                // test for writes
                Request putRoleRequest = new Request(HttpPut.METHOD_NAME, "/_security/role/test-role");
                putRoleRequest.setOptions(SECURITY_REQUEST_OPTIONS);
                putRoleRequest.setJsonEntity("""
                    { }
                    """);
                Response putRoleResponse = getRestClient().performRequest(putRoleRequest);
                assertThat(putRoleResponse.getStatusLine().getStatusCode(), equalTo(200));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // test for tokens
    public void testSecurityTokenIndex() throws Exception {
        SystemIndexThreadPoolUtils.runWithBlockedThreadPools(threadPoolsToBlock(), internalCluster(), () -> {
            try {
                Request postTokenRequest = new Request(HttpPost.METHOD_NAME, "/_security/oauth2/token");
                postTokenRequest.setOptions(SECURITY_REQUEST_OPTIONS);
                postTokenRequest.setJsonEntity("""
                    {
                        "grant_type": "client_credentials"
                    }
                    """);
                Response postTokenResponse = getRestClient().performRequest(postTokenRequest);
                assertThat(postTokenResponse.getStatusLine().getStatusCode(), equalTo(200));
                String access_token = (String) entityAsMap(postTokenResponse).get("access_token");
                Request invalidateTokenRequest = new Request(HttpDelete.METHOD_NAME, "/_security/oauth2/token");
                invalidateTokenRequest.setOptions(SECURITY_REQUEST_OPTIONS);
                invalidateTokenRequest.setJsonEntity(String.format("""
                    {
                        "token": "%s"
                    }
                    """, access_token));
                Response invalidateTokenResponse = getRestClient().performRequest(invalidateTokenRequest);
                assertThat(invalidateTokenResponse.getStatusLine().getStatusCode(), equalTo(200));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
