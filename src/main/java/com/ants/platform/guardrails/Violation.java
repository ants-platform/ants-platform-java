package com.ants.platform.guardrails;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Violation {

    @JsonProperty("scanner")
    private String scanner;

    @JsonProperty("details")
    private String details;

    @JsonProperty("action")
    private String action;

    public Violation() {}

    public String getScanner() { return scanner; }
    public String getDetails() { return details; }
    public String getAction() { return action; }

    @Override
    public String toString() {
        return scanner + ": " + (details != null ? details : "blocked");
    }
}
