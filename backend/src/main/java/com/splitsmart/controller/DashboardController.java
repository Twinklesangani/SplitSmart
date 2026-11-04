package com.splitsmart.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.splitsmart.dto.DashboardResponse;
import com.splitsmart.service.DashboardService;

@RestController
public class DashboardController {
    private final DashboardService dashboard;
    public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }
    @GetMapping("/api/dashboard")
    public DashboardResponse get(@AuthenticationPrincipal Jwt jwt) { return dashboard.get(jwt.getSubject()); }
}
