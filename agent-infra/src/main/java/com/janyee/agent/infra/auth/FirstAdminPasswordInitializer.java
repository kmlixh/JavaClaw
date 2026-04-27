package com.janyee.agent.infra.auth;

import com.janyee.agent.infra.persistence.entity.auth.AppUserEntity;
import com.janyee.agent.infra.persistence.repository.auth.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * 检测 V24 seed 下的 admin 用户 password_hash 是否还是占位值 PENDING_INITIALIZATION。
 * 是的话,生成一个强随机初始密码,BCrypt 入库,明文落到:
 *   1) stdout 打一条显眼的 log
 *   2) 项目根 ./FIRST_ADMIN_PASSWORD.txt 文件,仅 owner 可读(best-effort;Windows 上权限控制弱)
 *
 * 环境变量 AGENT_DEFAULT_ADMIN_PASSWORD 或 Spring property agent.auth.default-admin-password
 * 可以覆盖随机生成的密码(CI / 本地开发方便);未配置时走随机生成。
 *
 * <p>Order=LOWEST_PRECEDENCE 以便在 RunStartupRecoveryService 等其它 ApplicationRunner
 * 之后运行,避免 migration 刚跑完表还没 flush 到连接池的边界。</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class FirstAdminPasswordInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminPasswordInitializer.class);
    private static final String PENDING_PLACEHOLDER = "PENDING_INITIALIZATION";
    private static final String ADMIN_USER_ID = "admin";
    private static final int RANDOM_LENGTH = 20;
    private static final char[] ALPHABET = (
            "ABCDEFGHJKLMNPQRSTUVWXYZ" +       // 去掉 I O,避免混淆 l 和 0
            "abcdefghjkmnpqrstuvwxyz" +
            "23456789" +                        // 去掉 0 1
            "!@#$%&*-_=+?"
    ).toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private final AppUserRepository appUserRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Value("${agent.auth.default-admin-password:}")
    private String configuredPassword;

    public FirstAdminPasswordInitializer(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<AppUserEntity> adminOpt = appUserRepository.findById(ADMIN_USER_ID);
        if (adminOpt.isEmpty()) {
            log.warn("auth.admin_init.skip no admin user row found; V24 migration may not have run yet");
            return;
        }
        AppUserEntity admin = adminOpt.get();
        if (!PENDING_PLACEHOLDER.equals(admin.getPasswordHash())) {
            // 已经初始化过(正常情况 / 或由运维手动改过密码),不打扰
            return;
        }
        String envOverride = System.getenv("AGENT_DEFAULT_ADMIN_PASSWORD");
        String resolved = chooseInitialPassword(envOverride, configuredPassword);
        boolean generated = resolved == null;
        if (generated) {
            resolved = randomPassword();
        }
        admin.setPasswordHash(encoder.encode(resolved));
        admin.setPasswordMustChange(generated);   // 随机生成 → 强制首次登录改;env/configured 来的尊重运维判断
        appUserRepository.save(admin);

        if (generated) {
            announce(resolved);
        } else {
            log.info("auth.admin_init.done source={} passwordMustChange={} (password not printed)",
                    envOverride != null && !envOverride.isBlank() ? "env" : "property",
                    admin.isPasswordMustChange());
        }
    }

    private String chooseInitialPassword(String envOverride, String configured) {
        if (envOverride != null && !envOverride.isBlank()) return envOverride.trim();
        if (configured != null && !configured.isBlank()) return configured.trim();
        return null;
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 把生成的密码同时写到 stdout + 工作目录的 FIRST_ADMIN_PASSWORD.txt。
     * 两条路双保险:
     *   - log 让运维在 docker/k8s 场景直接从日志里捞出来;
     *   - 文件让单机启动的人就算日志被刷掉也能找到。
     * 文件写入失败不让启动挂掉,只记 warn。
     */
    private void announce(String password) {
        String banner = """

            ============================================================
               FIRST-RUN ADMIN PASSWORD (save this now, it won't repeat)
               username : admin
               password : %s
               tenant   : SYSTEM
               note     : first login will be forced to change this password
            ============================================================
            """.formatted(password);
        log.warn(banner);
        try {
            Path file = Path.of(System.getProperty("user.dir"), "FIRST_ADMIN_PASSWORD.txt");
            Files.writeString(file,
                    "# javaClaw auto-generated admin password\n"
                            + "username=admin\n"
                            + "password=" + password + "\n"
                            + "note=first-login will prompt for password change\n",
                    StandardCharsets.UTF_8);
            log.warn("auth.admin_init.password_file path={}", file.toAbsolutePath());
        } catch (Exception error) {
            log.warn("auth.admin_init.password_file_write_failed cause={}", error.getMessage());
        }
    }
}
