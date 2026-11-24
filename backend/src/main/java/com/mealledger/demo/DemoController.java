package com.mealledger.demo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mealledger.attendance.AttendanceService;
import com.mealledger.common.BusinessTime;
import com.mealledger.config.AppClock;
import com.mealledger.order.CutoffService;
import com.mealledger.order.MealSlot;
import com.mealledger.order.OrderGenerationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Demo controls: move the clock, reset data, simulate the rest of the program.
 * Exists only under the "demo" profile; production has no such endpoints.
 */
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoController implements ApplicationRunner {

    private final DemoData data;
    private final DemoSimulator simulator;
    private final AppClock clock;
    private final BusinessTime time;
    private final OrderGenerationService generation;
    private final CutoffService cutoff;
    private final AttendanceService attendance;
    private final JdbcClient jdbc;

    public DemoController(DemoData data, DemoSimulator simulator, AppClock clock, BusinessTime time,
                          OrderGenerationService generation, CutoffService cutoff, AttendanceService attendance,
                          JdbcClient jdbc) {
        this.data = data;
        this.simulator = simulator;
        this.clock = clock;
        this.time = time;
        this.generation = generation;
        this.cutoff = cutoff;
        this.attendance = attendance;
        this.jdbc = jdbc;
    }

    /** Seed on first start. */
    @Override
    public void run(ApplicationArguments args) {
        if (data.isEmpty()) {
            data.reset();
        }
    }

    public record DemoUser(String role, String label, String phone) {
    }

    /** anchorDay = the seeded "today"; serviceDay = the day the demo story serves. Both stay fixed while the clock moves. */
    public record DemoState(LocalDateTime nowIndia, LocalDate today, LocalDate nextDay,
                            LocalDateTime nextDayCutoffIndia, LocalDate anchorDay, LocalDate serviceDay,
                            String password, List<DemoUser> users) {
    }

    @GetMapping("/state")
    public DemoState state() {
        LocalDate today = time.today();
        LocalDate next = time.nextOperatingDay(today);
        String centre = jdbc.sql("SELECT name FROM centre WHERE id = :id").param("id", DemoData.FEATURED_CENTRE)
                .query(String.class).optional().orElse("Centre");
        String kitchen = jdbc.sql("SELECT name FROM vendor WHERE id = :id").param("id", DemoData.FEATURED_VENDOR)
                .query(String.class).optional().orElse("Kitchen");
        LocalDate anchor = jdbc.sql("SELECT MAX(service_date) FROM meal_order WHERE suggestion_basis = 'Imported history'")
                .query(LocalDate.class).optional().orElse(today);
        return new DemoState(
                time.now().atZoneSameInstant(time.zone()).toLocalDateTime(),
                today, next,
                time.cutoffFor(next).atZoneSameInstant(time.zone()).toLocalDateTime(),
                anchor, time.nextOperatingDay(anchor),
                DemoData.PASSWORD,
                List.of(new DemoUser("CENTRE", "Sunita · " + centre, DemoData.CENTRE_PHONE),
                        new DemoUser("KITCHEN", "Ramesh · " + kitchen, DemoData.KITCHEN_PHONE),
                        new DemoUser("OPS", "Saurabh · Ops team, Gurugram office", DemoData.OPS_PHONE)));
    }

    public record ClockRequest(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
    }

    /** Move "now" (India time). Then run what the scheduler would run, so the effect is immediate. */
    @PostMapping("/clock")
    public DemoState setClock(@Valid @RequestBody ClockRequest req) {
        clock.travelTo(req.at().atZone(time.zone()).toInstant());
        generation.ensureUpcoming();
        cutoff.lockDue(time.now());
        return state();
    }

    @PostMapping("/reset")
    public DemoState reset() {
        data.reset();
        return state();
    }

    public record MorningRequest(@NotNull LocalDate date, @NotNull MealSlot mealSlot) {
    }

    @PostMapping("/simulate/morning")
    public DemoSimulator.MorningResult morning(@Valid @RequestBody MorningRequest req) {
        return simulator.morning(req.date(), req.mealSlot());
    }

    public record InvoicesRequest(@NotNull LocalDate date) {
    }

    @PostMapping("/simulate/invoices")
    public DemoSimulator.InvoicesResult invoices(@Valid @RequestBody InvoicesRequest req) {
        return simulator.invoices(req.date());
    }

    public record FratRequest(@NotNull LocalDate date, @NotNull MealSlot mealSlot, @Min(0) @Max(5000) int present) {
    }

    /** Pretend to be the FRAT tablet at the featured centre. Goes through the real intake code. */
    @PostMapping("/frat")
    public AttendanceService.FratResult frat(@Valid @RequestBody FratRequest req) {
        return attendance.recordFrat(List.of(new AttendanceService.FratEntry(
                DemoData.FEATURED_CENTRE, req.date(), req.mealSlot(), req.present())));
    }
}
