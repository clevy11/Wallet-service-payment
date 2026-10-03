package com.example.walletservice.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.walletservice.domain.Customer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByOwnerId(UUID ownerId);
}