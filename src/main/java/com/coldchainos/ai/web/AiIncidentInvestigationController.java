package com.coldchainos.ai.web;

import com.coldchainos.ai.agent.AiIncidentCommanderAgent;
import com.coldchainos.ai.sop.SopKnowledgeService;
import com.coldchainos.ai.sop.SopPolicyDocument;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for the Gemini-powered AI Incident Commander and Pharmaceutical SOP RAG Knowledge Base.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiIncidentInvestigationController {

    private final AiIncidentCommanderAgent incidentCommander;
    private final SopKnowledgeService sopKnowledgeService;

    /**
     * Triggers the Gemini AI Incident Commander to autonomously investigate a temperature excursion.
     */
    @PostMapping("/incidents/investigate/{shipmentId}")
    public ResponseEntity<Map<String, Object>> investigateIncident(
        @PathVariable UUID shipmentId,
        @RequestHeader(value = "X-Tenant-ID", defaultValue = "pharma_corp") String tenantIdStr,
        @RequestParam(defaultValue = "Investigate temperature excursion and generate FDA CAPA report") String instruction
    ) {
        TenantId tenantId = TenantId.of(tenantIdStr);
        log.info("[AI Controller] Starting Gemini Incident Investigation for shipment '{}' [tenant='{}']",
            shipmentId, tenantId.value());

        String prompt = String.format("Shipment ID: %s | Tenant: %s | Prompt: %s",
            shipmentId, tenantId.value(), instruction);

        String investigationReport = TenantContext.executeAs(tenantId, () ->
            incidentCommander.investigateIncident(prompt)
        );

        return ResponseEntity.ok(Map.of(
            "shipmentId", shipmentId.toString(),
            "tenantId", tenantId.value(),
            "status", "INVESTIGATION_COMPLETED",
            "regulatoryStandard", "FDA 21 CFR Part 11 / WHO GDP",
            "reportMarkdown", investigationReport
        ));
    }

    /**
     * Semantic search over regulatory pharmaceutical SOP documents using vector embeddings.
     */
    @PostMapping("/sop/search")
    public ResponseEntity<List<String>> searchSops(
        @RequestParam String query,
        @RequestParam(defaultValue = "3") int maxResults
    ) {
        List<String> results = sopKnowledgeService.searchRelevantSops(query, maxResults);
        return ResponseEntity.ok(results);
    }

    /**
     * Returns the full catalog of indexed cold chain SOP stability documents.
     */
    @GetMapping("/sop/catalog")
    public ResponseEntity<List<SopPolicyDocument>> getSopCatalog() {
        return ResponseEntity.ok(sopKnowledgeService.getCatalog());
    }
}
