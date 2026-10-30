package com.tablewise.common;

import com.tablewise.security.Principals.DevicePrincipal;
import com.tablewise.security.Principals.PosAuth;
import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** One INFO line for every controller method that is called: which handler, who called, which outlet. No request data. */
@Configuration
class ApiLogInterceptor implements WebMvcConfigurer, HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger("api");

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (handler instanceof HandlerMethod h && req.getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST) {
            String caller = "anonymous";
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof PosAuth a && a.getPrincipal() instanceof StaffPrincipal s) caller = "staff role=" + s.role() + " staffId=" + s.staffId() + " outletId=" + s.outletId();
            else if (auth instanceof PosAuth a && a.getPrincipal() instanceof DevicePrincipal d) caller = "device deviceId=" + d.deviceId();
            else if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) caller = "authenticated";
            log.info("{}.{} {} {} caller=[{}]", h.getBeanType().getSimpleName(), h.getMethod().getName(), req.getMethod(), req.getRequestURI(), caller);
        }
        return true;
    }
}
