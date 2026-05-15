package com.campushub.backend.api;

import com.campushub.backend.auth.domain.User;
import com.campushub.backend.auth.domain.UserRole;
import com.campushub.backend.auth.domain.UserStatus;
import com.campushub.backend.auth.repository.UserRepository;
import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class DemoDataInitializer {

    @Bean
    CommandLineRunner seedUsers(UserRepository userRepository) {
        return args -> {
            if (userRepository.findByStudentId("admin").isEmpty()) {
                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                LocalDateTime now = LocalDateTime.now();
                userRepository.save(new User(
                    null,
                    "admin@campushub.local",
                    "admin",
                    encoder.encode("Admin1234"),
                    "系统管理员",
                    null,
                    UserRole.ADMIN,
                    UserStatus.ACTIVE,
                    100,
                    now,
                    now
                ));
            }
        };
    }
}
