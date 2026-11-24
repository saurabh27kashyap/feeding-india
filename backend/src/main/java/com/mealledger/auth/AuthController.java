package com.mealledger.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final JdbcClient jdbc;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authManager, JdbcClient jdbc) {
        this.authManager = authManager;
        this.jdbc = jdbc;
    }

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    public record Me(boolean authenticated, Long id, String name, Role role,
                     Long centreId, String centreName, Long vendorId, String vendorName) {
        static Me anonymous() {
            return new Me(false, null, null, null, null, null, null, null);
        }
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(req.phone(), req.password()));
        // New session id on login: prevents session fixation.
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(auth);
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return describe((AppPrincipal) auth.getPrincipal());
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AppPrincipal principal) {
        return principal == null ? Me.anonymous() : describe(principal);
    }

    private Me describe(AppPrincipal p) {
        String centreName = p.centreId() == null ? null
                : jdbc.sql("SELECT name FROM centre WHERE id = :id").param("id", p.centreId()).query(String.class).single();
        String vendorName = p.vendorId() == null ? null
                : jdbc.sql("SELECT name FROM vendor WHERE id = :id").param("id", p.vendorId()).query(String.class).single();
        return new Me(true, p.id(), p.name(), p.role(), p.centreId(), centreName, p.vendorId(), vendorName);
    }
}
