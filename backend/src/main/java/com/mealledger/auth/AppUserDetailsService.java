package com.mealledger.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AppPrincipal loadUserByUsername(String phone) {
        return jdbc.sql("""
                SELECT id, phone, name, password_hash, role, centre_id, vendor_id
                  FROM app_user WHERE phone = :phone""")
                .param("phone", phone)
                .query((rs, n) -> new AppPrincipal(
                        rs.getLong("id"),
                        rs.getString("phone"),
                        rs.getString("name"),
                        rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("centre_id", Long.class),
                        rs.getObject("vendor_id", Long.class)))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }
}
