package com.mealledger.ops;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mealledger.auth.AppPrincipal;
import com.mealledger.billing.InvoiceService;
import com.mealledger.common.ApiException;
import com.mealledger.common.BusinessTime;
import com.mealledger.order.Actor;
import com.mealledger.order.OrderRow;
import com.mealledger.order.OrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ops Console API (internal team). */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService ops;
    private final OrderService orders;
    private final InvoiceService invoices;
    private final BusinessTime time;

    public OpsController(OpsService ops, OrderService orders, InvoiceService invoices, BusinessTime time) {
        this.ops = ops;
        this.orders = orders;
        this.invoices = invoices;
        this.time = time;
    }

    @GetMapping("/dashboard")
    public OpsService.Dashboard dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ops.dashboard(date == null ? time.today() : date);
    }

    @GetMapping("/orders/{id}")
    public OpsService.OrderTimeline order(@PathVariable long id) {
        return ops.order(id);
    }

    public record OverrideRequest(@Min(0) @Max(5000) int qty, @NotBlank @Size(max = 200) String reason) {
    }

    @PutMapping("/orders/{id}/override")
    public OrderRow override(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                             @Valid @RequestBody OverrideRequest req) {
        return orders.overrideLockedCount(Actor.of(me), id, req.qty(), req.reason().trim());
    }

    /** Invoices for a range of service days (default: today only). */
    @GetMapping("/invoices")
    public List<InvoiceService.InvoiceSummary> invoices(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? time.today() : to;
        LocalDate start = from == null ? end : from;
        if (start.isBefore(end.minusDays(92))) {
            throw ApiException.unprocessable("RANGE_TOO_LONG", "Pick at most 92 days");
        }
        return invoices.list(start, end, null);
    }

    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @PostMapping("/invoices/{id}/reject")
    public InvoiceService.InvoiceDetail reject(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                               @Valid @RequestBody RejectRequest req) {
        return invoices.reject(Actor.of(me), id, req.reason().trim());
    }

    public record PayRequest(@NotBlank @Size(max = 40) String paymentRef) {
    }

    @PostMapping("/invoices/{id}/pay")
    public InvoiceService.InvoiceDetail pay(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                            @Valid @RequestBody PayRequest req) {
        return invoices.markPaid(Actor.of(me), id, req.paymentRef().trim().toUpperCase());
    }

    public record ConfirmRequest(@Min(0) @Max(5000) int qty, @NotBlank @Size(max = 200) String reason) {
    }

    @PutMapping("/orders/{id}/confirm")
    public OrderRow confirm(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                            @Valid @RequestBody ConfirmRequest req) {
        return orders.confirmOnBehalf(Actor.of(me), id, req.qty(), req.reason().trim());
    }

    @GetMapping("/invoices/{id}")
    public InvoiceService.InvoiceDetail invoice(@PathVariable long id) {
        return invoices.detail(id);
    }

    @PostMapping("/invoices/{id}/approve")
    public InvoiceService.InvoiceDetail approve(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        return invoices.approve(Actor.of(me), id);
    }

    @GetMapping("/scorecard")
    public List<OpsService.VendorScore> scorecard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ops.scorecard(date == null ? time.today() : date);
    }
}
