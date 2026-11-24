package com.mealledger.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in user. Each user is tied to exactly one centre or one kitchen,
 * and every query is scoped by that id on the server. The client never says
 * "I am centre 42"; the session does.
 */
public record AppPrincipal(long id, String phone, String name, String passwordHash,
                           Role role, Long centreId, Long vendorId) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return phone;
    }
}
