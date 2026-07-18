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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lightweight smoke test for the read-only ops endpoints: wiring + JSON shape.
 * Business semantics are covered by the service integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcurementOpsControllerTest {

    private static final String SHOP = "ops-ctrl-shop";

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
    void chainByTaskEndpointReturnsView() throws Exception {
        Long taskId = seedPending("L-ctrl-chain");

        mockMvc.perform(get("/api/plugin/procurement/ops/chain/by-task")
                        .param("shopName", SHOP)
                        .param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.taskStatus").value("PENDING"))
                .andExpect(jsonPath("$.deliveryStatus").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.hasExecution").value(false))
                .andExpect(jsonPath("$.execution").doesNotExist());
    }

    @Test
    void summaryEndpointReturnsCounts() throws Exception {
        seedPending("L-ctrl-summary");

        mockMvc.perform(get("/api/plugin/procurement/ops/summary")
                        .param("shopName", SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value(SHOP))
                .andExpect(jsonPath("$.taskStatusCounts.PENDING").value(1))
                .andExpect(jsonPath("$.deliveryStatusCounts.PENDING_DELIVERY").value(1));
    }

    @Test
    void anomaliesEndpointReturnsOk() throws Exception {
        seedPending("L-ctrl-anomaly");

        mockMvc.perform(get("/api/plugin/procurement/ops/anomalies")
                        .param("shopName", SHOP))
                .andExpect(status().isOk());
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
