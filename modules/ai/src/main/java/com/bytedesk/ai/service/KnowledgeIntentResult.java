package com.bytedesk.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured output returned by the optional small intent-classification model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeIntentResult {

    private String intent;

    private Double confidence;

    private String reason;
}
