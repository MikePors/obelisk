package com.example.demo;

public class AuthService {

    private final LoginController loginController = new LoginController();

    public boolean authenticate(String input) {
        return loginController.validateInput(input);
    }
}
