/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.repository.url;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.blobstore.url.http.URLHttpClient;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.RepositoryPlugin;
import org.elasticsearch.repositories.RepositoriesMetrics;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.url.URLRepository;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class URLRepositoryPlugin extends Plugin implements RepositoryPlugin {
    private final SetOnce<URLHttpClient.Factory> httpClientFactory = new SetOnce<>();

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(
            URLRepository.ALLOWED_URLS_SETTING,
            URLRepository.REPOSITORIES_URL_SETTING,
            URLRepository.SUPPORTED_PROTOCOLS_SETTING
        );
    }

    @Override
    public Map<String, Repository.Factory> getRepositories(
        Environment env,
        NamedXContentRegistry namedXContentRegistry,
        ClusterService clusterService,
        BigArrays bigArrays,
        RecoverySettings recoverySettings,
        RepositoriesMetrics repositoriesMetrics
    ) {
        return Collections.singletonMap(URLRepository.TYPE, metadata -> {
            assert httpClientFactory.get() != null : "Expected to get a configured http client factory";
            return new URLRepository(
                metadata,
                env,
                namedXContentRegistry,
                clusterService,
                bigArrays,
                recoverySettings,
                httpClientFactory.get()
            );
        });
    }

    @Override
    public Collection<?> createComponents(PluginServices services) {

        final URLHttpClient.Factory apacheURLHttpClientFactory = new URLHttpClient.Factory();

        httpClientFactory.set(apacheURLHttpClientFactory);
        return List.of(apacheURLHttpClientFactory);
    }

    @Override
    public void close() throws IOException {
        super.close();
        IOUtils.closeWhileHandlingException(httpClientFactory.get());
    }
}
