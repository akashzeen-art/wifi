package com.wifiextender.config;

import com.wifiextender.entity.License;
import com.wifiextender.entity.Plan;
import com.wifiextender.entity.Subscription;
import com.wifiextender.entity.User;
import com.wifiextender.repository.LicenseRepository;
import com.wifiextender.repository.PlanRepository;
import com.wifiextender.repository.SubscriptionRepository;
import com.wifiextender.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    public static final String DEMO_EMAIL = "demo@wifiextender.com";
    public static final String DEMO_PASSWORD = "demo123";

    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final LicenseRepository licenseRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedAdmin();
        seedDemoUser();
    }

    private void seedAdmin() {
        userRepository.findByEmail("admin@wifiextender.com").ifPresentOrElse(
            admin -> {
                if (!admin.isActive() || admin.getLockedUntil() != null || admin.getFailedAttempts() > 0) {
                    admin.setActive(true);
                    admin.setFailedAttempts(0);
                    admin.setLockedUntil(null);
                    userRepository.save(admin);
                    log.info("Admin account unlocked");
                }
            },
            () -> {
                User admin = new User();
                admin.setName("Admin");
                admin.setEmail("admin@wifiextender.com");
                admin.setPassword(passwordEncoder.encode("admin123"));
                admin.setRole(User.Role.ADMIN);
                userRepository.save(admin);
                log.info("Seeded admin: admin@wifiextender.com / admin123");
            }
        );
    }

    /** Demo USER with ACTIVE Premium subscription + license — for sharing with testers. */
    private void seedDemoUser() {
        User demo = userRepository.findByEmail(DEMO_EMAIL).orElseGet(() -> {
            User u = new User();
            u.setName("Demo User");
            u.setEmail(DEMO_EMAIL);
            u.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
            u.setRole(User.Role.USER);
            u.setActive(true);
            userRepository.save(u);
            log.info("Seeded demo user: {} / {}", DEMO_EMAIL, DEMO_PASSWORD);
            return u;
        });

        if (subscriptionRepository.findActiveByUserId(demo.getId()).isPresent()) {
            log.info("Demo user already has an active subscription");
            return;
        }

        Plan plan = planRepository.findByNameIgnoreCase("Premium")
            .or(() -> planRepository.findByNameIgnoreCase("Basic"))
            .or(() -> planRepository.findByActiveTrueOrderBySortOrderAscPriceAsc().stream().findFirst())
            .orElse(null);

        if (plan == null) {
            log.warn("No plans found — skip demo subscription (run migrations / create plans first)");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setUser(demo);
        sub.setPlan(plan);
        sub.setStatus(Subscription.Status.ACTIVE);
        sub.setStartsAt(now);
        sub.setActivatedAt(now);
        sub.setAdminNotes("Demo account — auto-seeded for testing");
        if (!plan.isLifetime()) {
            int days = plan.getDurationDays() != null && plan.getDurationDays() > 0
                ? plan.getDurationDays() : 30;
            // Give demo users 90 days for testing
            sub.setExpiresAt(now.plusDays(Math.max(days, 90)));
        }
        subscriptionRepository.save(sub);

        License license = new License();
        license.setSubscription(sub);
        license.setUser(demo);
        license.setLicenseKey(generateKey());
        license.setStatus(License.Status.ACTIVE);
        license.setExpiresAt(sub.getExpiresAt() != null ? sub.getExpiresAt() : now.plusYears(100));
        license.setMaxActivations(5);
        licenseRepository.save(license);

        log.info("Demo subscription ACTIVE on plan '{}' — license: {}", plan.getName(), license.getLicenseKey());
    }

    private String generateKey() {
        String raw = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12)
            + "-" + raw.substring(12, 16) + "-" + raw.substring(16, 20);
    }
}
