package com.miraassistant.controller;

import com.miraassistant.model.UserCalendar;
import com.miraassistant.repo.UserCalendarRepository;
import com.miraassistant.service.GoogleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class MainController {

    private final GoogleService googleService;
    private final UserCalendarRepository repo;

    @Value("${frontendRedirect}")
    private String frontendRedirect;

    public MainController(GoogleService googleService, UserCalendarRepository repo) {
        this.googleService = googleService;
        this.repo = repo;
    }

    // STEP 1 — Generate Google OAuth URL
    @GetMapping("/auth/google")
    public ResponseEntity<?> authGoogle() {
        String url = googleService.buildAuthUrl(null);
        return ResponseEntity.ok(Map.of("url", url));
    }

    // STEP 2 — Google redirects here with ?code=
    @GetMapping("/oauth2/callback")
    public ResponseEntity<?> callback(@RequestParam String code) {
        try {
            Map<String, Object> tokenInfo = googleService.exchangeCodeForTokens(code);

            UserCalendar temp = new UserCalendar();
            temp.setAuthCode(null);
            if (tokenInfo.get("email") != null) temp.setEmail((String) tokenInfo.get("email"));
            if (tokenInfo.get("access_token") != null) temp.setAccessToken((String) tokenInfo.get("access_token"));
            if (tokenInfo.get("refresh_token") != null) temp.setRefreshToken((String) tokenInfo.get("refresh_token"));
            if (tokenInfo.get("expires_in") != null) {
                long expiresIn = (Long) tokenInfo.get("expires_in");
                temp.setTokenExpiry(Instant.now().plusSeconds(expiresIn - 60));
            }
            temp.setCalendarId("primary");

            repo.save(temp);

            String target = frontendRedirect + "?authId=" + temp.getId();
            return ResponseEntity.status(302).location(URI.create(target)).build();

        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // Lightweight endpoint so frontend can fetch temp email by authId (only returns email)
    @GetMapping("/temp/{id}")
    public ResponseEntity<?> getTemp(@PathVariable String id) {
        return repo.findById(id)
                .map(u -> ResponseEntity.ok(Map.of("email", u.getEmail())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found", "message", "Temp auth record not found")));
    }

    // STEP 3 — User enters phone manually (email prefilled & readonly)
    @PostMapping("/connect-phone")
    public ResponseEntity<?> connectPhone(@RequestBody Map<String,String> body) {
        try {
            String authId = body.get("authId");
            String email = body.get("email");
            String phone = body.get("phoneNumber");

            if (authId == null || email == null || phone == null)
                return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", "authId, email & phone are required"));

            // Fetch temporary auth record (this is the same record created at /oauth2/callback)
            UserCalendar temp = repo.findById(authId)
                    .orElseThrow(() -> new RuntimeException("Invalid authId"));

            // If temp does not have tokens, fail early (we expect tokens saved at callback)
            if (temp.getAccessToken() == null) {
                return ResponseEntity.status(400).body(Map.of("error", "no_tokens", "message", "OAuth tokens not found. Please re-authenticate."));
            }

            // --- SAFER lookup: use list-based lookups so we never get a non-unique exception ---
            List<UserCalendar> emailMatches = repo.findByEmail(email);
            List<UserCalendar> phoneMatches = repo.findByPhoneNumber(phone);

            // If any email match exists that is NOT the temp record -> conflict
            boolean emailConflict = emailMatches.stream()
                    .anyMatch(u -> !u.getId().equals(temp.getId()));

            if (emailConflict) {
                return ResponseEntity.status(409)
                        .body(Map.of("error", "email_in_use", "message", "This Google account is already used by another phone."));
            }

            // If any phone match exists that is NOT the temp record -> conflict
            boolean phoneConflict = phoneMatches.stream()
                    .anyMatch(u -> !u.getId().equals(temp.getId()));

            if (phoneConflict) {
                return ResponseEntity.status(409)
                        .body(Map.of("error", "phone_in_use", "message", "This phone number is already used by another account."));
            }

            // Update same record with email (if missing) and phone
            temp.setEmail(email);
            temp.setPhoneNumber(phone);

            // Save with duplicate-key protection
            try {
                repo.save(temp);
            } catch (DuplicateKeyException dke) {
                // This can happen if concurrent write created a duplicate between our check and save
                dke.printStackTrace();
                return ResponseEntity.status(409).body(Map.of(
                        "error", "duplicate_key",
                        "message", "Conflict while linking account. Another record was created concurrently with the same email or phone."
                ));
            }

            return ResponseEntity.ok(Map.of("status", "linked", "email", temp.getEmail(), "phone", temp.getPhoneNumber()));

        } catch (IncorrectResultSizeDataAccessException ire) {
            // Defensive: in case some other repository method still expected single result and DB returned many
            ire.printStackTrace();
            return ResponseEntity.status(409).body(Map.of(
                    "error", "duplicate_records",
                    "message", "Multiple records found for this email/phone. Please contact support."
            ));
        } catch (RuntimeException rex) {
            rex.printStackTrace();
            return ResponseEntity.status(400).body(Map.of("error", "bad_request", "message", rex.getMessage()));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // CREATE EVENT
    @PostMapping("/webhook/create")
    public ResponseEntity<?> webhookCreate(@RequestBody Map<String,String> body) {
        try {
            String phone = body.get("phoneNumber");
            String date = body.get("date");
            String time = body.get("time");
            String title = body.getOrDefault("title", "New Event");

            Map<String,Object> res = googleService.createEvent(phone, title, date, time);
            return ResponseEntity.ok(Map.of("status","created","result", res));

        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // RESCHEDULE EVENT (by event name)
    @PostMapping("/webhook/reschedule")
    public ResponseEntity<?> webhookReschedule(@RequestBody Map<String,String> body) {
        try {
            String phone = body.get("phoneNumber");
            String eventName = body.get("eventName");
            String newDate = body.get("newDate");
            String newTime = body.get("newTime");

            Map<String,Object> res = googleService.rescheduleEventByName(phone, eventName, newDate, newTime);
            return ResponseEntity.ok(Map.of("status","rescheduled","result", res));

        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // CANCEL EVENT (by event name + date + time)
    @PostMapping("/webhook/cancel")
    public ResponseEntity<?> webhookCancel(@RequestBody Map<String,String> body) {
        try {
            String phone = body.get("phoneNumber");
            String eventName = body.get("eventName");
            String date = body.get("date");
            String time = body.get("time");

            Map<String, Object> res = googleService.cancelEventByName(phone, eventName, date, time);

            return ResponseEntity.ok(Map.of(
                    "status", "cancelled",
                    "result", res
            ));

        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // READ ALL EVENTS (current / trigger)
    @GetMapping("/calendar/read")
    public ResponseEntity<?> readCurrentEvent(@RequestParam String phoneNumber) {
        try {
            Map<String, Object> event = googleService.getCurrentEvent(phoneNumber);
            return ResponseEntity.ok(Map.of("status", "success", "currentEvent", event));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "internal_error", "message", ex.getMessage()));
        }
    }

    // READ EVENT BY NAME + DATE + TIME
    @PostMapping("/calendar/read-by-name")
    public ResponseEntity<?> readEventByName(@RequestBody Map<String,String> body) {
        try {
            String phone = body.get("phoneNumber");
            String eventName = body.get("eventName");
            String date = body.get("date");
            String time = body.get("time");

            Map<String,Object> event = googleService.readEventByName(phone, eventName, date, time);

            return ResponseEntity.ok(Map.of(
                    "status", "found",
                    "event", event
            ));

        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(404).body(Map.of("error", "not_found", "message", ex.getMessage()));
        }
    }
}
