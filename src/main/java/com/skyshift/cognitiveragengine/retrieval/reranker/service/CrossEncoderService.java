package com.skyshift.cognitiveragengine.retrieval.reranker.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

/**
 * Scores a single (query, passage) pair with a local ONNX export of
 * cross-encoder/ms-marco-MiniLM-L-6-v2. Model + tokenizer are loaded once at startup;
 * OrtSession.run is safe to call concurrently from multiple threads.
 */
@Slf4j
@Service
public class CrossEncoderService {

    private final RetrievalProperties.Reranker properties;
    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public CrossEncoderService(RetrievalProperties retrievalProperties) {
        this.properties = retrievalProperties.getReranker();
    }

    @PostConstruct
    void loadModel() {
        if (!properties.isEnabled()) {
            log.info("Cross-encoder reranker disabled; skipping model load");
            return;
        }
        try {
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(properties.getModelPath(), new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(
                    Path.of(properties.getTokenizerPath()),
                    Map.of(
                            "truncation", "true",
                            "maxLength", String.valueOf(properties.getMaxSequenceLength())
                    )
            );
            log.info("Cross-encoder model loaded from {}", properties.getModelPath());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load cross-encoder model/tokenizer from " + properties.getModelPath(), e);
        }
    }

    /**
     * Returns a relevance score in [0, 1] (sigmoid of the model's raw logit) for the pair.
     */
    public double score(String query, String passage) {
        Encoding encoding = tokenizer.encode(query, passage);
        try (
                OnnxTensor inputIds = OnnxTensor.createTensor(environment, new long[][]{encoding.getIds()});
                OnnxTensor attentionMask = OnnxTensor.createTensor(environment, new long[][]{encoding.getAttentionMask()});
                OnnxTensor tokenTypeIds = OnnxTensor.createTensor(environment, new long[][]{encoding.getTypeIds()})
        ) {
            Map<String, OnnxTensor> inputs = Map.of(
                    "input_ids", inputIds,
                    "attention_mask", attentionMask,
                    "token_type_ids", tokenTypeIds
            );
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                double logit = logits[0][0];
                return 1.0 / (1.0 + Math.exp(-logit));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Cross-encoder scoring failed", e);
        }
    }

    @PreDestroy
    void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException e) {
            log.warn("Failed to close ONNX session cleanly", e);
        }
    }
}