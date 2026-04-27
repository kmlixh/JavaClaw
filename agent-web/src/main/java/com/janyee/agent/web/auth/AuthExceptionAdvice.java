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
        HttpStatus status = isForbidden(error.code()) ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).body(Map.of(
                "code", error.code(),
                "message", error.getMessage()
        ));
    }

    private boolean isForbidden(String code) {
        if (code == null) return false;
        return code.equalsIgnoreCase("forbidden")
                || code.equalsIgnoreCase("TENANT_FORBIDDEN");
    }
}
