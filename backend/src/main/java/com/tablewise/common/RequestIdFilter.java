package com.tablewise.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a request id (and later outletId / orderId) on every log line, and logs one line per HTTP request when it ends:
 * method, path, status, milliseconds. Query strings and bodies are never logged (they can hold phone numbers).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; } // CSV exports stream on an async dispatch

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        MDC.put("requestId", id != null ? id : UUID.randomUUID().toString());
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            int status = failure != null ? 500 : res.getStatus();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (!req.getRequestURI().startsWith("/actuator/health")) { // health probes would drown everything else
                if (status >= 500) log.error("{} {} -> {} in {} ms", req.getMethod(), req.getRequestURI(), status, ms);
                else if (status >= 400) log.warn("{} {} -> {} in {} ms", req.getMethod(), req.getRequestURI(), status, ms);
                else log.info("{} {} -> {} in {} ms", req.getMethod(), req.getRequestURI(), status, ms);
            }
            MDC.clear();
        }
    }
}
