package com.agentforge.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * Java gateway to the Claude API. Every model call in the application goes through here.
 * When no credentials are configured, {@link #available()} is false and callers must fall back
 * to their rule-based implementation or report the feature as unavailable.
 */
@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** A binary attachment (image or PDF) sent alongside the prompt. */
    public record Attachment(String mediaType, byte[] data) {
        public boolean isImage() {
            return mediaType != null && mediaType.startsWith("image/");
        }

        public boolean isPdf() {
            return "application/pdf".equals(mediaType);
        }
    }

    private final String model;
    private final String effort;
    private final AnthropicClient client;
    private final Semaphore concurrency = new Semaphore(4);

    public AiService(@Value("${agentforge.ai.model}") String model, @Value("${agentforge.ai.effort}") String effort,
                     @Value("${agentforge.ai.enabled:true}") boolean enabled) {
        this.model = model;
        this.effort = effort;
        AnthropicClient c = null;
        // Only an explicit application key is used, so the app never borrows credentials meant for other tools.
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (enabled && key != null && !key.isBlank()) {
            try {
                c = AnthropicOkHttpClient.builder().apiKey(key.trim()).build();
            } catch (Exception e) {
                log.warn("Claude client could not be created: {}", e.getMessage());
            }
        }
        this.client = c;
        log.info("AI service: {}", c == null ? "UNAVAILABLE (set ANTHROPIC_API_KEY to enable)" : "Claude " + model);
    }

    public boolean available() {
        return client != null;
    }

    public String model() {
        return model;
    }

    public String complete(String system, String user, int maxTokens) {
        return complete(system, user, List.of(), maxTokens);
    }

    /** Sends a single request and returns the concatenated text output. */
    public String complete(String system, String user, List<Attachment> attachments, int maxTokens) {
        if (client == null) throw new AiUnavailableException("Claude API is not configured (ANTHROPIC_API_KEY not set)");
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Attachment a : attachments) {
            String b64 = Base64.getEncoder().encodeToString(a.data());
            if (a.isImage()) {
                blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder().data(b64).mediaType(imageType(a.mediaType())).build())
                        .build()));
            } else if (a.isPdf()) {
                blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                        .source(Base64PdfSource.builder().data(b64).build())
                        .build()));
            }
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(user).build()));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .system(system)
                .outputConfig(OutputConfig.builder().effort(effortLevel()).build())
                .addUserMessageOfContentBlockParams(blocks)
                // Server-side refusal fallback: if the primary model declines, the API retries on a suitable model.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        try {
            concurrency.acquire();
            Message response = client.messages().create(params);
            String stop = response.stopReason().map(Object::toString).orElse("");
            if ("refusal".equalsIgnoreCase(stop)) {
                throw new AiUnavailableException("The AI service declined this request");
            }
            StringBuilder sb = new StringBuilder();
            response.content().forEach(b -> b.text().ifPresent(t -> sb.append(t.text())));
            if (sb.isEmpty()) throw new IllegalStateException("AI service returned no text (stop reason: " + stop + ")");
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for AI service");
        } finally {
            concurrency.release();
        }
    }

    private OutputConfig.Effort effortLevel() {
        return switch (effort.toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    private static Base64ImageSource.MediaType imageType(String mt) {
        return switch (mt) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
    }
}
