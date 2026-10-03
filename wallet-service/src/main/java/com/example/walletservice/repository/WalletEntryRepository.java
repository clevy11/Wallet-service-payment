package com.example.walletservice.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.walletservice.domain.WalletEntry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, UUID> {

    Optional<WalletEntry> findByReference(String reference);

    boolean existsByReference(String reference);
}