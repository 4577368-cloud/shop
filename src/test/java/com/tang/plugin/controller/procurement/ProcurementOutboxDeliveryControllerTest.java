package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lightweight smoke test for the outbox delivery endpoints: verifies wiring and JSON shape.
 * Business semantics are covered by the service/repository integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcurementOutboxDeliveryControllerTest {

    private static final String SHOP = "ctrl-test-shop";

    @Autowired
    private MockMvc mockMvc;
    @Resource
    private ThirdPlatformProcurementTaskRepository repository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void pullEndpointReturnsPulledCount() throws Exception {
        seedPending("L-ctrl-pull");

        mockMvc.perform(post("/api/plugin/procurement/outbox/pull")
                        .param("shopName", SHOP)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value(SHOP))
                .andExpect(jsonPath("$.pulled").value(1))
                .andExpect(jsonPath("$.tasks[0].lineId").value("L-ctrl-pull"));
    }

    @Test
    void ackEndpointReturnsDelivered() throws Exception {
        seedPending("L-ctrl-ack");

        mockMvc.perform(post("/api/plugin/procurement/outbox/ack")
                        .param("shopName", SHOP)
                        .param("lineId", "L-ctrl-ack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELIVERED"));
    }

    private Long seedPending(String lineId) {
        return repository.saveIfAbsent(new ThirdPlatformProcurementTask()
                .setShopName(SHOP)
                .setShopType("SHOPIFY")
                .setOuterOrderId("O-" + lineId)
                .setLineId(lineId)
                .setTangbuySkuId("SKU-" + lineId)
                .setQuantity(1)
                .setTaskStatus(ProcurementTaskStatus.PENDING)
                .setDelFlag(0));
    }
}
