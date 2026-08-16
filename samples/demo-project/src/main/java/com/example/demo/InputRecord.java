package com.example.demo;

public record InputRecord(String value) {

    public boolean isPresent() {
        return value != null && !value.isBlank();
    }
}
