package dev.e2b.sdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reason for the current template build status, typically populated when a build fails.
 */
@Data
@NoArgsConstructor
public class BuildStatusReason {
    private String message;

    private String step;

    @JsonProperty("logEntries")
    private List<BuildLogEntry> logEntries;
}
