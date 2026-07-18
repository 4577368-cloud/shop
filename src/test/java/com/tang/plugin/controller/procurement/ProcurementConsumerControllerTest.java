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
 * Lightweight smoke test for the consumer integration endpoints: wiring + JSON shape.
 * Business semantics are covered by the service/repository integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcurementConsumerControllerTest {

    private static final String SHOP = "consumer-ctrl-shop";
    private static final String CONSUMER = "main-platform";

    @Autowired
    private MockMvc mockMvc;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void receiveEndpointReturnsReceived() throws Exception {
        Long taskId = seedPending("L-ctrl-recv");

        mockMvc.perform(post("/api/plugin/procurement/consumer/receive")
                        .param("shopName", SHOP)
                        .param("taskId", String.valueOf(taskId))
                        .param("consumerId", CONSUMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumptionOutcome").value("RECEIVED"))
                .andExpect(jsonPath("$.consumptionStatus").value("RECEIVED"));
    }

    @Test
    void acceptEndpointReturnsAcceptedAndDelivered() throws Exception {
        Long taskId = seedPending("L-ctrl-accept");

        mockMvc.perform(post("/api/plugin/procurement/consumer/accept")
                        .param("shopName", SHOP)
                        .param("taskId", String.valueOf(taskId))
                        .param("consumerId", CONSUMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumptionOutcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.deliveryOutcome").value("DELIVERED"));
    }

    private Long seedPending(String lineId) {
        return taskRepository.saveIfAbsent(new ThirdPlatformProcurementTask()
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
