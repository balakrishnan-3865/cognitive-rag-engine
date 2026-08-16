package com.skyshift.cognitiveragengine.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DoclingProperties.class)
public class DoclingClientConfiguration {

    @Bean
    public RestClient doclingRestClient(RestClient.Builder builder, DoclingProperties props) {
        return builder.baseUrl(props.baseUrl()).build();
    }
}
