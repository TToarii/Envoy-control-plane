package com.example.demogrpcserver.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class HomeRestController {


    @GetMapping
    public ResponseEntity<Object> hello() {

        return ResponseEntity.ok("Hello world");
    }


}
