package com.skyshift.cognitiveragengine.qa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalProperties {
    private Dense dense = new Dense();
    private Sparse sparse = new Sparse();
    private Fusion fusion = new Fusion();

    @Getter
    @Setter
    public static class Dense {
        private int topK = 30;
        private double similarityThreshold = 0.65;
    }

    @Getter
    @Setter
    public static class Sparse {
        private int topK = 30;
        private double minScorePercentile = 0.5;
    }

    @Getter
    @Setter
    public static class Fusion {
        private String strategy = "rrf";
        private int rrfK = 60;
        private Weights weights = new Weights();
    }

    @Getter
    @Setter
    public static class Weights {
        private double dense = 0.5;
        private double sparse = 0.5;
    }
}