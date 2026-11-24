package com.mealledger.kitchen;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mealledger.auth.AppPrincipal;
import com.mealledger.billing.InvoiceService;
import com.mealledger.common.BusinessTime;
import com.mealledger.order.Actor;
import com.mealledger.order.MealSlot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Partner App, kitchen side. Every call is scoped to the logged-in vendor. */
@RestController
@RequestMapping("/api/kitchen")
public class KitchenController {

    private final KitchenService kitchen;
    private final InvoiceService invoices;
    private final BusinessTime time;

    public KitchenController(KitchenService kitchen, InvoiceService invoices, BusinessTime time) {
        this.kitchen = kitchen;
        this.invoices = invoices;
        this.time = time;
    }

    public record Days(LocalDate today, LocalDate nextDay) {
    }

    @GetMapping("/days")
    public Days days() {
        return new Days(time.today(), time.nextOperatingDay(time.today()));
    }

    @GetMapping("/prep-list")
    public KitchenService.PrepList prepList(@AuthenticationPrincipal AppPrincipal me,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return kitchen.prepList(me.vendorId(), date);
    }

    public record DispatchRequest(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @NotNull MealSlot mealSlot) {
    }

    @PostMapping("/routes/{routeId}/dispatch")
    public KitchenService.DispatchResult dispatch(@AuthenticationPrincipal AppPrincipal me, @PathVariable long routeId,
                                                  @Valid @RequestBody DispatchRequest req) {
        return kitchen.dispatch(Actor.of(me), routeId, req.date(), req.mealSlot());
    }

    @GetMapping("/changes")
    public List<KitchenService.Change> changes(@AuthenticationPrincipal AppPrincipal me,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return kitchen.changes(me.vendorId(), date);
    }

    @GetMapping("/money")
    public KitchenService.Money money(@AuthenticationPrincipal AppPrincipal me,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return kitchen.money(me.vendorId(), date);
    }

    @GetMapping("/reviews")
    public KitchenService.Reviews reviews(@AuthenticationPrincipal AppPrincipal me,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return kitchen.reviews(me.vendorId(), date);
    }

    @GetMapping("/invoice-draft")
    public InvoiceService.Draft draft(@AuthenticationPrincipal AppPrincipal me,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return invoices.draft(me.vendorId(), date);
    }

    public record LineRequest(long orderId, @Min(0) @Max(5000) int billedQty) {
    }

    public record InvoiceRequest(@NotNull LocalDate serviceDate,
                                 @NotNull @Size(min = 1, max = 2000) List<@Valid LineRequest> lines) {
    }

    public record InvoiceCreated(long invoiceId) {
    }

    /** This kitchen's bills for the last 30 days (default), newest first. */
    @GetMapping("/invoices")
    public List<InvoiceService.InvoiceSummary> myBills(@AuthenticationPrincipal AppPrincipal me) {
        return invoices.list(time.today().minusDays(30), time.today(), me.vendorId());
    }

    @GetMapping("/invoices/{id}")
    public InvoiceService.InvoiceDetail myBill(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        return invoices.detailForVendor(me.vendorId(), id);
    }

    public record ResubmitRequest(@NotNull @Size(min = 1, max = 2000) List<@Valid LineRequest> lines) {
    }

    @PostMapping("/invoices/{id}/resubmit")
    public InvoiceCreated resubmit(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                   @Valid @RequestBody ResubmitRequest req) {
        long invoiceId = invoices.resubmit(Actor.of(me), id,
                req.lines().stream().map(l -> new InvoiceService.SubmitLine(l.orderId(), l.billedQty())).toList());
        return new InvoiceCreated(invoiceId);
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceCreated submit(@AuthenticationPrincipal AppPrincipal me, @Valid @RequestBody InvoiceRequest req) {
        long id = invoices.submit(Actor.of(me), req.serviceDate(),
                req.lines().stream().map(l -> new InvoiceService.SubmitLine(l.orderId(), l.billedQty())).toList());
        return new InvoiceCreated(id);
    }
}
