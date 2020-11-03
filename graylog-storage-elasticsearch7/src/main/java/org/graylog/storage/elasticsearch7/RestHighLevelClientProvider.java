package org.graylog.storage.elasticsearch7;

import com.github.joschi.jadconfig.util.Duration;
import org.graylog.shaded.elasticsearch7.org.apache.http.HttpHost;
import org.graylog.shaded.elasticsearch7.org.apache.http.client.CredentialsProvider;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.RestClient;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.RestClientBuilder;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.RestHighLevelClient;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.sniff.ElasticsearchNodesSniffer;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.sniff.NodesSniffer;
import org.graylog.shaded.elasticsearch7.org.elasticsearch.client.sniff.Sniffer;
import org.graylog2.system.shutdown.GracefulShutdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Singleton
public class RestHighLevelClientProvider implements Provider<RestHighLevelClient> {

    private static final Logger LOG = LoggerFactory.getLogger(RestHighLevelClientProvider.class);

    private final RestHighLevelClient client;
    private final Sniffer sniffer;

    @SuppressWarnings("unused")
    @Inject
    public RestHighLevelClientProvider(
            GracefulShutdownService shutdownService,
            @Named("elasticsearch_hosts") List<URI> hosts,
            @Named("elasticsearch_connect_timeout") Duration connectTimeout,
            @Named("elasticsearch_socket_timeout") Duration socketTimeout,
            @Named("elasticsearch_idle_timeout") Duration elasticsearchIdleTimeout,
            @Named("elasticsearch_max_total_connections") int maxTotalConnections,
            @Named("elasticsearch_max_total_connections_per_route") int maxTotalConnectionsPerRoute,
            @Named("elasticsearch_max_retries") int elasticsearchMaxRetries,
            @Named("elasticsearch_discovery_enabled") boolean discoveryEnabled,
            @Named("elasticsearch_discovery_filter") @Nullable String discoveryFilter,
            @Named("elasticsearch_discovery_frequency") Duration discoveryFrequency,
            @Named("elasticsearch_discovery_default_scheme") String defaultSchemeForDiscoveredNodes,
            @Named("elasticsearch_use_expect_continue") boolean useExpectContinue,
            CredentialsProvider credentialsProvider) {
        client = buildClient(
                hosts,
                connectTimeout,
                socketTimeout,
                maxTotalConnections,
                maxTotalConnectionsPerRoute,
                useExpectContinue,
                credentialsProvider);

        sniffer = discoveryEnabled
                ? createNodeDiscoverySniffer(client.getLowLevelClient(), discoveryFrequency, defaultSchemeForDiscoveredNodes, discoveryFilter)
                : null;

        registerShutdownHook(shutdownService);

        if (discoveryEnabled) {
            registerSnifferShutdownHook(shutdownService);
        }
    }

    private Sniffer createNodeDiscoverySniffer(RestClient restClient, Duration discoveryFrequency, String defaultSchemeForDiscoveredNodes, String discoveryFilter) {
        final NodesSniffer nodesSniffer = FilteredElasticsearchNodesSniffer.create(
                restClient,
                TimeUnit.SECONDS.toMillis(5),
                mapDefaultScheme(defaultSchemeForDiscoveredNodes),
                discoveryFilter
        );
        return Sniffer.builder(restClient)
                .setSniffIntervalMillis(Math.toIntExact(discoveryFrequency.toMilliseconds()))
                .setNodesSniffer(nodesSniffer)
                .build();
    }

    private ElasticsearchNodesSniffer.Scheme mapDefaultScheme(String defaultSchemeForDiscoveredNodes) {
        switch (defaultSchemeForDiscoveredNodes.toUpperCase(Locale.ENGLISH)) {
            case "HTTP": return ElasticsearchNodesSniffer.Scheme.HTTP;
            case "HTTPS": return ElasticsearchNodesSniffer.Scheme.HTTPS;
            default: throw new IllegalArgumentException("Invalid default scheme for discovered ES nodes: " + defaultSchemeForDiscoveredNodes);
        }
    }

    @Override
    public RestHighLevelClient get() {
        return client;
    }

    private RestHighLevelClient buildClient(
            List<URI> hosts,
            Duration connectTimeout,
            Duration socketTimeout,
            int maxTotalConnections,
            int maxTotalConnectionsPerRoute,
            boolean useExpectContinue,
            CredentialsProvider credentialsProvider) {
        final HttpHost[] esHosts = hosts.stream().map(uri -> new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())).toArray(HttpHost[]::new);

        final RestClientBuilder restClientBuilder = RestClient.builder(esHosts)
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(Math.toIntExact(connectTimeout.toMilliseconds()))
                        .setSocketTimeout(Math.toIntExact(socketTimeout.toMilliseconds()))
                        .setExpectContinueEnabled(useExpectContinue)
                        .setAuthenticationEnabled(true)
                )
                .setHttpClientConfigCallback(httpClientConfig -> httpClientConfig
                        .setMaxConnTotal(maxTotalConnections)
                        .setMaxConnPerRoute(maxTotalConnectionsPerRoute)
                        .setDefaultCredentialsProvider(credentialsProvider)
                );

        return new RestHighLevelClient(restClientBuilder);
    }

    private void registerShutdownHook(GracefulShutdownService shutdownService) {
        shutdownService.register(() -> {
            try {
                client.close();
            } catch (IOException e) {
                LOG.warn("Failed to close Elasticsearch RestHighLevelClient", e);
            }
        });
    }

    private void registerSnifferShutdownHook(GracefulShutdownService shutdownService) {
        shutdownService.register(sniffer::close);
    }
}