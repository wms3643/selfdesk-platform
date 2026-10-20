package com.bytedesk.ai.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured result of the business-intent classifier. The model may return
 * multiple intents because a customer can ask for several things at once.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessIntentResult {

    private String primaryIntent;

    private List<BusinessIntentMatch> intents = new ArrayList<>();

    private String reason;
}
