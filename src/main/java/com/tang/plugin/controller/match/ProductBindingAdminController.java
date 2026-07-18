package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.ConfirmBindingDTO;
import com.tang.plugin.domain.dto.match.GenerateMatchCandidateDTO;
import com.tang.plugin.domain.dto.match.ManualBindingDTO;
import com.tang.plugin.domain.dto.match.SkuBindingView;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.service.match.ProductBindingQueryService;
import com.tang.plugin.service.match.ProductBindingService;
import com.tang.plugin.service.match.ProductMatchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal integration endpoints for product binding (NOT a workbench UI).
 * Minimal surface for local/联调 verification of the P1 binding flow.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/product/binding")
public class ProductBindingAdminController {

    @Resource
    private ProductMatchService productMatchService;
    @Resource
    private ProductBindingService productBindingService;
    @Resource
    private ProductBindingQueryService productBindingQueryService;

    @PostMapping("/candidates/generate")
    public Map<String, Object> generateCandidates(@RequestBody GenerateMatchCandidateDTO dto) {
        int count = productMatchService.generateCandidates(dto);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("generated", count);
        return body;
    }

    @GetMapping("/candidates/pending")
    public List<ShopProductMatchCandidate> listPending(@RequestParam String shopName) {
        return productMatchService.listPending(shopName);
    }

    @PostMapping("/candidates/reject")
    public Map<String, Object> rejectCandidate(@RequestParam String shopName, @RequestParam Long candidateId) {
        productMatchService.rejectCandidate(shopName, candidateId);
        return ok();
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmBindingDTO dto) {
        productBindingService.confirmBinding(dto);
        return ok();
    }

    @PostMapping("/manual")
    public Map<String, Object> bindManually(@RequestBody ManualBindingDTO dto) {
        productBindingService.bindManually(dto);
        return ok();
    }

    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestParam String shopName, @RequestParam String thirdPlatformSkuId) {
        productBindingService.unbind(shopName, thirdPlatformSkuId);
        return ok();
    }

    @GetMapping("/active")
    public Map<String, Object> findActive(@RequestParam String shopName, @RequestParam String thirdPlatformSkuId) {
        Optional<SkuBindingView> view = productBindingQueryService.findActiveSkuBinding(shopName, thirdPlatformSkuId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("bound", view.isPresent());
        body.put("binding", view.orElse(null));
        return body;
    }

    private Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        return body;
    }
}
