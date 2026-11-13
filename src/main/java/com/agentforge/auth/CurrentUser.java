package com.agentforge.auth;

import com.agentforge.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;

/** Resolves the signed-in user id from the HTTP session and guards /api/** routes. */
@Component
public class CurrentUser implements HandlerInterceptor {
    public static final String SESSION_KEY = "agentforge.uid";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        HttpSession s = req.getSession(false);
        if (s == null || s.getAttribute(SESSION_KEY) == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            try {
                res.getWriter().write("{\"error\":\"Not signed in\"}");
            } catch (Exception ignored) {
            }
            return false;
        }
        return true;
    }

    /** Id of the user making the current request. */
    public long id() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpSession s = attrs == null ? null : attrs.getRequest().getSession(false);
        Object v = s == null ? null : s.getAttribute(SESSION_KEY);
        if (v == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in");
        return (Long) v;
    }
}
