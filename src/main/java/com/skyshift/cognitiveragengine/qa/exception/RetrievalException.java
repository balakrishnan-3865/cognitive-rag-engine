package com.skyshift.cognitiveragengine.qa.exception;

public class RetrievalException extends RuntimeException {
    private final String failedSources;
    private final Exception denseException;
    private final Exception sparseException;

    /**
     * Both dense and sparse retrievals failed.
     */
    public RetrievalException(Exception denseEx, Exception sparseEx) {
        super(formatMessage(denseEx, sparseEx));
        this.failedSources = "both";
        this.denseException = denseEx;
        this.sparseException = sparseEx;
    }

    /**
     * Single source retrieval failed (used in Phase 2).
     */
    public RetrievalException(String source, Exception cause) {
        super("Retrieval failed from " + source + ": " + cause.getMessage(), cause);
        this.failedSources = source;
        this.denseException = source.equals("dense") ? cause : null;
        this.sparseException = source.equals("sparse") ? cause : null;
    }

    private static String formatMessage(Exception denseEx, Exception sparseEx) {
        return String.format(
            "Both retrieval sources failed. Dense: %s, Sparse: %s",
            denseEx.getMessage(),
            sparseEx.getMessage()
        );
    }

    public String getFailedSources() {
        return failedSources;
    }

    public Exception getDenseException() {
        return denseException;
    }

    public Exception getSparseException() {
        return sparseException;
    }
}