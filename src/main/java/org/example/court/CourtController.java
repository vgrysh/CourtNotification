package org.example.court;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CourtController {

    private final CourtService courtService;
    private final ClubService clubService;

    public CourtController(CourtService courtService, ClubService clubService) {
        this.courtService = courtService;
        this.clubService = clubService;
    }

    @GetMapping("/clubs")
    public ResponseEntity<?> clubs() {
        try {
            return ResponseEntity.ok(clubService.fetchClubs());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam String club,
            @RequestParam String date,
            @RequestParam(required = false) String court,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        try {
            List<AvailableSlot> slots = courtService.checkAvailability(club, date, court, from, to);
            return ResponseEntity.ok(slots);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
