package org.opensearch.dataprepper.plugins.source.source_crawler.base;


import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.PaginationCrawlerWorkerProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.CrowdStrikeWorkerProgressState;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaginationCrawlerWorkerProgressState.class, name = "pagination_crawler"),
        @JsonSubTypes.Type(value = CrowdStrikeWorkerProgressState.class,  name = "crowdstrike")
})
public interface SaasWorkerProgressState {
}
