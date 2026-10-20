package com.bytedesk.ai.demo_order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoOrderApiControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DemoOrderApiController("test-token"))
            .build();

    @Test
    void returnsOrderForAuthorizedOrderNumberLookup() throws Exception {
        mockMvc.perform(post("/internal/demo/orders/query")
                        .header("X-Bytedesk-Tool-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","intent":"ORDER_QUERY","slots":{"orderNo":"A123456"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.orderNo").value("A123456"))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.logistics.trackingNo").value("SF123456789"));
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/internal/demo/orders/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-2","intent":"ORDER_QUERY","slots":{"orderNo":"A123456"}}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void returnsFoundFalseForUnknownOrder() throws Exception {
        mockMvc.perform(post("/internal/demo/orders/query")
                        .header("X-Bytedesk-Tool-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-3","intent":"ORDER_QUERY","slots":{"orderNo":"UNKNOWN-1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }
}
