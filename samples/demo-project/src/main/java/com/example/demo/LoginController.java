package com.example.demo;

public class LoginController {

    public boolean validateInput(String input) {
        return input != null && !input.isBlank();
    }

    public void handleLogin(String input) {
        if (validateInput(input)) {
            System.out.println("login accepted");
        } else {
            System.out.println("login rejected");
        }
    }
}
