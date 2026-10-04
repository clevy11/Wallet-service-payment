package com.example.walletservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Required, or @Scheduled on OutboxRelay never runs. This fails silently: the
// application starts, every request succeeds, the outbox row is written -- and no
// event is ever published, so no downstream service learns the transfer happened.
// Nothing throws and no test fails unless one asserts the relay was scheduled.
@EnableScheduling
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }

}
