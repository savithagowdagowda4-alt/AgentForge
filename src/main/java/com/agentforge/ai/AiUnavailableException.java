package com.agentforge.ai;

/** Raised when the AI service is not configured or declines a request. */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message) {
        super(message);
    }
}
