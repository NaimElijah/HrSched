package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import java.net.http.HttpClient;

/** Breaks the I/O rule with the JDK alone: a use case holding an HTTP client. */
public record ServiceCallingOverHttp(HttpClient http) {}
