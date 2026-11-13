package com.agentforge.auth;

import com.agentforge.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body, HttpServletRequest req) {
        auth.checkSignupCode(body.get("inviteCode"));
        Map<String, Object> user = auth.register(body.get("username"), body.get("password"), body.get("displayName"));
        startSession(req, user);
        return user;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest req) {
        Map<String, Object> user = auth.login(body.get("username"), body.get("password"));
        startSession(req, user);
        return user;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("inviteCodeRequired", auth.signupCodeRequired());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object uid = s == null ? null : s.getAttribute(CurrentUser.SESSION_KEY);
        if (uid == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in");
        return auth.profile((Long) uid);
    }

    private static void startSession(HttpServletRequest req, Map<String, Object> user) {
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        HttpSession s = req.getSession(true);
        s.setMaxInactiveInterval(60 * 60 * 12);
        s.setAttribute(CurrentUser.SESSION_KEY, ((Number) user.get("id")).longValue());
    }
}
