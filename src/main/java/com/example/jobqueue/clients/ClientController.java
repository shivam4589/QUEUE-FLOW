package com.example.jobqueue.clients;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration of API clients. All of it needs the admin login when one is configured (see AdminWriteFilter). */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientService service;

    public ClientController(ClientService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiClient> list() {
        return service.list();
    }

    /** Creates a client. The response contains the key, shown only this once. */
    @PostMapping
    public ResponseEntity<NewClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        NewClientResponse created = service.create(request.name());
        return ResponseEntity.created(URI.create("/api/clients/" + created.client().id())).body(created);
    }

    @PostMapping("/{id}/disable")
    public ApiClient disable(@PathVariable UUID id) {
        return service.setEnabled(id, false);
    }

    @PostMapping("/{id}/enable")
    public ApiClient enable(@PathVariable UUID id) {
        return service.setEnabled(id, true);
    }
}
