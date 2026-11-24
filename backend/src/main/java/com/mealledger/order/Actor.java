package com.mealledger.order;

import com.mealledger.auth.AppPrincipal;
import com.mealledger.auth.Role;

/** Who is making a change. Recorded on every audit event. */
public record Actor(Long userId, String label, Role role, Long centreId, Long vendorId) {

    public static Actor of(AppPrincipal p) {
        return new Actor(p.id(), p.name(), p.role(), p.centreId(), p.vendorId());
    }

    public static Actor system(String job) {
        return new Actor(null, "SYSTEM:" + job, null, null, null);
    }
}
