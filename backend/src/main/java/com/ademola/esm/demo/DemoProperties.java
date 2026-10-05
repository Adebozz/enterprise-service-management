package com.ademola.esm.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared password for all demo accounts ({@code ESM_DEMO_PASSWORD}). Local demos only. */
@ConfigurationProperties("esm.demo")
public record DemoProperties(String password) {}
