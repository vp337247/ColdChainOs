package com.coldchainos.ai.sop;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Knowledge Base & RAG service for pharmaceutical Standard Operating Procedures (SOPs).
 * Indexes stability curves, temperature thresholds, and excursion budgets using vector embeddings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SopKnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private final List<SopPolicyDocument> catalog = new ArrayList<>();

    @PostConstruct
    public void initializeSopKnowledgeBase() {
        log.info("[SOP Knowledge Base] Ingesting industry-standard pharmaceutical cold chain stability guidelines...");

        registerSop(new SopPolicyDocument(
            "SOP-MRNA-01",
            "ULTRA_COLD_FROZEN",
            "Ultra-Low Temperature mRNA Vaccine Transport Stability",
            "Section 3.1 & 4.2",
            "Storage condition: -80.0°C to -60.0°C in dry ice shippers. An allowable excursion up to -15.0°C " +
            "is permitted during airport apron transit or customs inspection for a cumulative maximum duration of 120 minutes. " +
            "If excursion temperature exceeds -15.0°C or duration exceeds 120 minutes, lipid nanoparticle integrity degrades, " +
            "mandating immediate quarantine and batch disposal.",
            BigDecimal.valueOf(-80.0),
            BigDecimal.valueOf(-60.0),
            BigDecimal.valueOf(-15.0),
            120
        ));

        registerSop(new SopPolicyDocument(
            "SOP-INS-02",
            "REFRIGERATED",
            "Refrigerated Biologics & Insulin Glargine Stability Protocol",
            "Section 2.4",
            "Standard storage condition: +2.0°C to +8.0°C. Transient temperature excursions between +8.0°C and +15.0°C " +
            "are tolerable for a cumulative period not exceeding 240 minutes (4 hours) without loss of biological potency. " +
            "CRITICAL HAZARD: Freezing below 0.0°C causes irreversible protein denaturation; any reading below 0.0°C for > 5 minutes " +
            "renders the product condemned.",
            BigDecimal.valueOf(2.0),
            BigDecimal.valueOf(8.0),
            BigDecimal.valueOf(15.0),
            240
        ));

        registerSop(new SopPolicyDocument(
            "SOP-PLAS-03",
            "CONTROLLED_ROOM_TEMPERATURE",
            "Human Plasma Protein & Monoclonal Antibody Specifications",
            "Section 5.0",
            "Controlled Room Temperature (CRT) range: +15.0°C to +25.0°C. Temperature spikes between +25.0°C and +30.0°C " +
            "are acceptable during summer distribution for up to 720 minutes (12 hours). Temperatures exceeding +32.0°C " +
            "trigger immediate microbial risk assessment and quarantine.",
            BigDecimal.valueOf(15.0),
            BigDecimal.valueOf(25.0),
            BigDecimal.valueOf(30.0),
            720
        ));

        registerSop(new SopPolicyDocument(
            "SOP-CELL-04",
            "CRYOGENIC",
            "Cryogenic CAR-T Cell and Gene Therapy Product Transport",
            "Section 1.2",
            "Cryopreserved in liquid nitrogen dry vapor shippers at <= -150.0°C. ZERO TOLERANCE for warming above -120.0°C. " +
            "Any warming above -120.0°C for even 60 seconds causes fatal ice recrystallization and immediate cell death.",
            BigDecimal.valueOf(-196.0),
            BigDecimal.valueOf(-150.0),
            BigDecimal.valueOf(-120.0),
            0
        ));

        log.info("[SOP Knowledge Base] Successfully indexed {} pharmaceutical stability SOPs into vector store", catalog.size());
    }

    public void registerSop(SopPolicyDocument doc) {
        catalog.add(doc);

        String fullText = String.format("SOP ID: %s | Cargo Type: %s | Title: %s | Section: %s | Guidelines: %s",
            doc.sopId(), doc.cargoType(), doc.title(), doc.section(), doc.textContent());

        Metadata metadata = new Metadata();
        metadata.put("sopId", doc.sopId());
        metadata.put("cargoType", doc.cargoType());
        metadata.put("title", doc.title());

        TextSegment segment = TextSegment.from(fullText, metadata);
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }

    /**
     * Searches SOP policies using cosine similarity over embedding vectors.
     */
    public List<String> searchRelevantSops(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, maxResults, 0.4);

        List<String> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            results.add(String.format("[Score: %.2f] %s", match.score(), match.embedded().text()));
        }

        if (results.isEmpty() && !catalog.isEmpty()) {
            // Fallback to keyword matching if embedding score threshold is too high
            for (SopPolicyDocument doc : catalog) {
                if (query.toUpperCase().contains(doc.cargoType()) || doc.textContent().toLowerCase().contains(query.toLowerCase())) {
                    results.add(String.format("[Fallback Match] SOP ID: %s (%s): %s", doc.sopId(), doc.title(), doc.textContent()));
                }
            }
        }

        return results;
    }

    public List<SopPolicyDocument> getCatalog() {
        return Collections.unmodifiableList(catalog);
    }
}
