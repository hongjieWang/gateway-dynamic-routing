package com.july.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("server-b")
@RestController
public class Controller {
    @GetMapping
    public String getServerName() {
        return "Server B !!!";
    }
}
