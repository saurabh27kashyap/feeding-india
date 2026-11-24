package com.mealledger.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mealledger.attendance.AttendanceService.FratEntry;
import com.mealledger.common.ApiException;
import com.mealledger.config.MealLedgerProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Machine-to-machine: FRAT pushes attendance counts here with an API key. */
@RestController
@RequestMapping("/api/integrations/frat")
public class FratController {

    private final AttendanceService attendance;
    private final byte[] apiKey;

    public FratController(AttendanceService attendance, MealLedgerProperties props) {
        this.attendance = attendance;
        this.apiKey = props.fratApiKey().getBytes(StandardCharsets.UTF_8);
    }

    public record FratRequest(@NotNull @Size(min = 1, max = 5000) List<@Valid FratEntry> entries) {
    }

    @PostMapping("/attendance")
    public AttendanceService.FratResult attendance(@RequestHeader(name = "X-Api-Key", required = false) String key,
                                                   @Valid @RequestBody FratRequest req) {
        // Constant-time compare, so the key cannot be guessed from response timing.
        if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_API_KEY", "Missing or wrong API key");
        }
        return attendance.recordFrat(req.entries());
    }
}
