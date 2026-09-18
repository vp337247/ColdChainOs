package com.coldchainos.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manages distributed correlation and SLF4J Mapped Diagnostic Context (MDC)
 * across synchronous threads and asynchronous Kafka messaging boundaries.
 *
 * Propagates:
 * - traceId: Global end-to-end distributed trace identifier.
 * - spanId: Current execution segment identifier.
 * - tenantId: Current tenant isolation scope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrelationContext {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TENANT_ID = "tenantId";

    public static final String HEADER_TRACE_ID = "X-Trace-ID";
    public static final String HEADER_SPAN_ID = "X-Span-ID";

    @Nullable
    private final Tracer tracer;

    /**
     * Resolves active trace ID from Micrometer Tracer if available, or generates a deterministic fallback.
     */
    public String getOrCreateTraceId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().traceId();
        }
        String mdcTrace = MDC.get(MDC_TRACE_ID);
        if (mdcTrace != null && !mdcTrace.isBlank()) {
            return mdcTrace;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Resolves active span ID from Micrometer Tracer if available, or generates a fallback.
     */
    public String getOrCreateSpanId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().spanId();
        }
        String mdcSpan = MDC.get(MDC_SPAN_ID);
        if (mdcSpan != null && !mdcSpan.isBlank()) {
            return mdcSpan;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Binds correlation metadata into SLF4J MDC.
     */
    public void bindToMdc(@Nullable String traceId, @Nullable String spanId, @Nullable String tenantId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        if (spanId != null && !spanId.isBlank()) {
            MDC.put(MDC_SPAN_ID, spanId);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            MDC.put(MDC_TENANT_ID, tenantId);
        }
    }

    /**
     * Clears ColdChainOS-specific keys from SLF4J MDC.
     */
    public void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_TENANT_ID);
    }

    /**
     * Executes a runnable within a scoped correlation context, restoring previous context on completion.
     */
    public void runWithCorrelation(
        @Nullable String traceId,
        @Nullable String spanId,
        @Nullable String tenantId,
        Runnable action
    ) {
        String prevTrace = MDC.get(MDC_TRACE_ID);
        String prevSpan = MDC.get(MDC_SPAN_ID);
        String prevTenant = MDC.get(MDC_TENANT_ID);

        String effectiveTrace = (traceId != null && !traceId.isBlank()) ? traceId : getOrCreateTraceId();
        String effectiveSpan = (spanId != null && !spanId.isBlank()) ? spanId : getOrCreateSpanId();

        bindToMdc(effectiveTrace, effectiveSpan, tenantId);
        try {
            action.run();
        } finally {
            restoreMdc(prevTrace, prevSpan, prevTenant);
        }
    }

    /**
     * Executes a supplier within a scoped correlation context, restoring previous context on completion.
     */
    public <T> T callWithCorrelation(
        @Nullable String traceId,
        @Nullable String spanId,
        @Nullable String tenantId,
        Supplier<T> action
    ) {
        String prevTrace = MDC.get(MDC_TRACE_ID);
        String prevSpan = MDC.get(MDC_SPAN_ID);
        String prevTenant = MDC.get(MDC_TENANT_ID);

        String effectiveTrace = (traceId != null && !traceId.isBlank()) ? traceId : getOrCreateTraceId();
        String effectiveSpan = (spanId != null && !spanId.isBlank()) ? spanId : getOrCreateSpanId();

        bindToMdc(effectiveTrace, effectiveSpan, tenantId);
        try {
            return action.get();
        } finally {
            restoreMdc(prevTrace, prevSpan, prevTenant);
        }
    }

    private void restoreMdc(String trace, String span, String tenant) {
        if (trace != null) MDC.put(MDC_TRACE_ID, trace); else MDC.remove(MDC_TRACE_ID);
        if (span != null) MDC.put(MDC_SPAN_ID, span); else MDC.remove(MDC_SPAN_ID);
        if (tenant != null) MDC.put(MDC_TENANT_ID, tenant); else MDC.remove(MDC_TENANT_ID);
    }
}
