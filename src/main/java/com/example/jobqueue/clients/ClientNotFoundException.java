package com.example.jobqueue.clients;

import java.util.UUID;

public class ClientNotFoundException extends RuntimeException {
    public ClientNotFoundException(UUID id) {
        super("API client not found: " + id);
    }
}
