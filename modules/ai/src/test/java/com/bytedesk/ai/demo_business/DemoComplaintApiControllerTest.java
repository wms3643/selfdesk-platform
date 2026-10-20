package com.bytedesk.ai.demo_business;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoComplaintApiControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DemoComplaintApiController("test-token"))
            .build();

    @Test
    void createsComplaintAndReturnsStableNumberForIdempotentRetry() throws Exception {
        String body = """
                {"requestId":"req-1","intent":"COMPLAINT","slots":{
                  "reason":"订单三天没有发货","orderNo":"A123456"}}
                """;
        String first = mockMvc.perform(post("/internal/demo/complaints/create")
                        .header("X-Bytedesk-Tool-Token", "test-token")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String complaintNo = com.alibaba.fastjson2.JSON.parseObject(first).getString("complaintNo");

        mockMvc.perform(post("/internal/demo/complaints/create")
                        .header("X-Bytedesk-Tool-Token", "test-token")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.complaintNo").value(complaintNo));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/internal/demo/complaints/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-2","intent":"COMPLAINT","slots":{"reason":"订单三天没有发货"}}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsShortComplaintReason() throws Exception {
        mockMvc.perform(post("/internal/demo/complaints/create")
                        .header("X-Bytedesk-Tool-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-3","intent":"COMPLAINT","slots":{"reason":"太差"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("COMPLAINT_REASON_REQUIRED"));
    }
}
