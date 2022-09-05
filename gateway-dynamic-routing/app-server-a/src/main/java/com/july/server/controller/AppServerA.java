package com.july.server.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class AppServerA {

    public static void main(String[] args) {
        SpringApplication.run(AppServerA.class, args);
    }
}
