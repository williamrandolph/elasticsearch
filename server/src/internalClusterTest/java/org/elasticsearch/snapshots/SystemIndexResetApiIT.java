/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.snapshots;

import org.elasticsearch.action.admin.cluster.snapshots.features.ResetFeatureStateAction;
import org.elasticsearch.action.admin.cluster.snapshots.features.ResetFeatureStateRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;

public class SystemIndexResetApiIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(SystemIndexTestPlugin.class);
        return plugins;
    }

    public void testResetSystemIndices() throws Exception {
        // put a document in a system index
        indexDoc(SystemIndexTestPlugin.SYSTEM_INDEX_PATTERN, "1", "purpose", "pre-snapshot doc");
        refresh(SystemIndexTestPlugin.SYSTEM_INDEX_PATTERN);

        // put a document in associated index
        indexDoc(SystemIndexTestPlugin.ASSOCIATED_INDEX_NAME, "1", "purpose", "pre-snapshot doc");
        refresh(SystemIndexTestPlugin.ASSOCIATED_INDEX_NAME);

        // call the reset API
        client().execute(ResetFeatureStateAction.INSTANCE, new ResetFeatureStateRequest()).get();

        // verify that both indices are gone
        Exception e1 = expectThrows(IndexNotFoundException.class, () -> client().admin().indices().prepareGetIndex()
            .addIndices(SystemIndexTestPlugin.SYSTEM_INDEX_PATTERN)
            .get());

        assertThat(e1.getMessage(), containsString("no such index"));

        Exception e2 = expectThrows(IndexNotFoundException.class, () -> client().admin().indices().prepareGetIndex()
            .addIndices(SystemIndexTestPlugin.ASSOCIATED_INDEX_NAME)
            .get());

        assertThat(e2.getMessage(), containsString("no such index"));
    }

    public static class SystemIndexTestPlugin extends Plugin implements SystemIndexPlugin {

        public static final String SYSTEM_INDEX_PATTERN = ".test-system-idx";
        public static final String ASSOCIATED_INDEX_NAME = ".associated-idx";

        @Override
        public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
            return Collections.singletonList(new SystemIndexDescriptor(SYSTEM_INDEX_PATTERN + "*", "System indices for tests"));
        }

        @Override
        public Collection<String> getAssociatedIndexPatterns() {
            return Collections.singletonList(ASSOCIATED_INDEX_NAME);
        }

        @Override
        public String getFeatureName() {
            return SystemIndicesSnapshotIT.SystemIndexTestPlugin.class.getSimpleName();
        }

        @Override
        public String getFeatureDescription() {
            return "A simple test plugin";
        }
    }

}
