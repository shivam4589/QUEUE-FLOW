package com.example.jobqueue.limits;

/** running = how many jobs of this type are running right now. */
public record TypeLimit(String type, int maxConcurrent, long running) {
}
