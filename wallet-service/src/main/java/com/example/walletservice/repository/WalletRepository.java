package com.example.walletservice.repository;

import java.util.List;
import java.util.UUID;

import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByCustomerId(UUID customerId);

    List<Wallet> findByCustomerIdAndStatus(UUID customerId, WalletStatus status);
}