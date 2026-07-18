package com.tang.plugin.controller.procurement;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import com.tang.plugin.service.procurement.ProcurementConsumerIntegrationService;
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
 * Lightweight smoke test for the execution stub endpoints: wiring + JSON shape.
 * Business semantics are covered by the service integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcurementExecutionControllerTest {

    private static final String SHOP = "exec-ctrl-shop";
    private static final String CONSUMER = "main-platform";

    @Autowired
    private MockMvc mockMvc;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private ProcurementConsumerIntegrationService consumerService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_execution WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void createThenCompleteEndpoints() throws Exception {
        Long taskId = acceptedTask("L-ctrl-exec");

        mockMvc.perform(post("/api/plugin/procurement/execution/create")
                        .param("shopName", SHOP)
                        .param("taskId", String.valueOf(taskId))
                        .param("consumerId", CONSUMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.executionStatus").value("PENDING_EXECUTION"));

        mockMvc.perform(post("/api/plugin/procurement/execution/complete")
                        .param("shopName", SHOP)
                        .param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.executionStatus").value("COMPLETED_STUB"));
    }

    private Long acceptedTask(String lineId) {
        Long taskId = taskRepository.saveIfAbsent(new ThirdPlatformProcurementTask()
                .setShopName(SHOP)
                .setShopType("SHOPIFY")
                .setOuterOrderId("O-" + lineId)
                .setLineId(lineId)
                .setTangbuySkuId("SKU-" + lineId)
                .setQuantity(1)
                .setTaskStatus(ProcurementTaskStatus.PENDING)
                .setDelFlag(0));
        consumerService.accept(SHOP, taskId, CONSUMER, null);
        return taskId;
    }
}
