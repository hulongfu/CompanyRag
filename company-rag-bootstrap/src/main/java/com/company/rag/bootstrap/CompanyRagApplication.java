package com.company.rag.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.company.rag")
public class CompanyRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompanyRagApplication.class, args);
    }
}
