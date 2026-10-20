package com.bytedesk.ai.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Second-stage structured output for one selected business intent.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessSlotResult {

    private String intent;

    private List<BusinessSlot> slots = new ArrayList<>();

    private String reason;
}
