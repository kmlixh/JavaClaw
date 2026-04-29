package com.janyee.agent.web.auth;

import com.janyee.agent.infra.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把 PermissionGate / AuthService 抛出的 AuthException 翻译成 HTTP:
 *   code="forbidden" / "TENANT_FORBIDDEN" → 403
 *   其他("USER_NOT_FOUND" / "INVALID_CREDENTIALS" / ...) → 401
 *
 * 各 controller 自己 try/catch 的优先级更高,这里是兜底。
 */
@RestControllerAdvice
public class AuthExceptionAdvice {

    @ExceptionHandler(AuthService.AuthException.class)
    public ResponseEntity<?> handleAuth(AuthService.AuthException error) {
        HttpStatus status;
        if ("NOT_FOUND".equalsIgnoreCase(error.code())) {
            status = HttpStatus.NOT_FOUND;
        } else if (isForbidden(error.code())) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.UNAUTHORIZED;
        }
        return ResponseEntity.status(status).body(Map.of(
                "code", error.code(),
                "message", error.getMessage()
        ));
    }

    /**
     * service 层 RBAC 编辑权限不足时抛 SecurityException,这里统一转 403。
     * (避免 service 直接 import spring-web 的 ResponseStatusException)。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> handleForbidden(SecurityException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "forbidden",
                "message", error.getMessage() == null ? "permission denied" : error.getMessage()
        ));
    }

    private boolean isForbidden(String code) {
        if (code == null) return false;
        return code.equalsIgnoreCase("forbidden")
                || code.equalsIgnoreCase("TENANT_FORBIDDEN");
    }
}
