package com.example.jobqueue.limits;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/limits")
public class LimitController {

    private final LimitService service;

    public LimitController(LimitService service) {
        this.service = service;
    }

    @GetMapping
    public List<TypeLimit> list() {
        return service.list();
    }

    /** Creates the limit for a job type or changes it. */
    @PostMapping
    public TypeLimit set(@Valid @RequestBody SetLimitRequest request) {
        return service.set(request.type(), request.maxConcurrent());
    }

    /** Removes the limit; the type is unrestricted again. */
    @DeleteMapping("/{type}")
    public TypeLimit remove(@PathVariable String type) {
        return service.remove(type);
    }
}
