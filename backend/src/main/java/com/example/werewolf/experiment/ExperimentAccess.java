package com.example.werewolf.experiment;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 独立的 HttpOnly 访问凭据，让同一浏览器在后端重启后仍能查看自己的持久化结果。 */
@Component
public class ExperimentAccess {
    public static final String COOKIE_NAME = "werewolf-experiment-owner";
    public String owner(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && cookie.getValue().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                return cookie.getValue();
        }
        return null;
    }
    public String getOrCreate(HttpServletRequest request, HttpServletResponse response) {
        // 活跃对局仍由原有 HttpSession 授权；缺失 cookie 时恢复该会话的实验凭据。
        Object remembered = request.getSession().getAttribute(COOKIE_NAME);
        String owner = remembered instanceof String value ? value : owner(request);
        if (owner == null) owner = UUID.randomUUID().toString();
        request.getSession().setAttribute(COOKIE_NAME, owner);
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, owner).httpOnly(true)
                .secure(request.isSecure()).sameSite("Strict").path("/api")
                .maxAge(Duration.ofDays(365)).build().toString());
        return owner;
    }
}
