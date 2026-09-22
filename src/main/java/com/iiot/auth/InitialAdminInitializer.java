package com.iiot.auth;

import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class InitialAdminInitializer implements ApplicationRunner {
    private final AuthRepository users;
    private final AuthService auth;
    private final AuthProperties p;
    private final TransactionTemplate transactions;
    private final Validator validator;

    public InitialAdminInitializer(AuthRepository users, AuthService auth, AuthProperties p,
                                   TransactionTemplate transactions, Validator validator) {
        this.users = users;
        this.auth = auth;
        this.p = p;
        this.transactions = transactions;
        this.validator = validator;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            users.jdbc().queryForObject("SELECT id FROM telemetry.auth_bootstrap_lock WHERE id=1 FOR UPDATE", Integer.class);
            if (users.jdbc().queryForObject("SELECT COUNT(*) FROM telemetry.auth_users WHERE role='ADMIN'", Integer.class) > 0)
                return;
            var request = new AdminUserController.NewUser(p.initialAdminUsername(), p.initialAdminEmail(), p.initialAdminPassword(), AuthRepository.Role.ADMIN);
            if (!validator.validate(request).isEmpty())
                throw new IllegalStateException("Configure AUTH_INITIAL_ADMIN_USERNAME, AUTH_INITIAL_ADMIN_EMAIL and AUTH_INITIAL_ADMIN_PASSWORD for first startup");
            auth.create(request.username(), request.email(), request.password(), request.role());
        });
    }
}
