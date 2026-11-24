package com.mealledger.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The frontend is a single-page app: its routes all load index.html. */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/story", "/partner", "/partner/**", "/ops", "/ops/**"})
    public String index() {
        return "forward:/index.html";
    }
}
