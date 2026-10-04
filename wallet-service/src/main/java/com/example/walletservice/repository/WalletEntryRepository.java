package com.example.walletservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;

import com.example.walletservice.domain.WalletEntry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, UUID> {

    Optional<WalletEntry> findByReference(String reference);

    /**
     * A wallet's history, newest first.
     *
     * <p>Paged and bounded rather than returning every entry ever: a wallet that has
     * been open for years has millions of rows, and an endpoint that reads them all is
     * an outage waiting for a popular account. The index
     * {@code idx_wallet_entries_wallet (wallet_id, created_at DESC)} matches this
     * ordering exactly, so no sort is needed.
     */
    List<WalletEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId,
                                                         org.springframework.data.domain.Pageable pageable);

    boolean existsByReference(String reference);
}