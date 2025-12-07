package com.miraassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miraassistant.model.UserCalendar;
import com.miraassistant.repo.UserCalendarRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.net.URI;
import java.util.*;

@Service
public class GoogleService {

    private final UserCalendarRepository repo;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${google.clientId}")
    private String clientId;

    @Value("${google.clientSecret}")
    private String clientSecret;

    @Value("${google.redirectUri}")
    private String redirectUri;

    public GoogleService(UserCalendarRepository repo) {
        this.repo = repo;
    }

    // Build auth URL with openid & email so id_token is returned
    public String buildAuthUrl(String state) {
        String scope = "openid email profile https://www.googleapis.com/auth/calendar";

        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + clientId
                + "&response_type=code"
                + "&scope=" + scope
                + "&access_type=offline"
                + "&prompt=consent"
                + "&redirect_uri=" + redirectUri;
    }

    /**
     * Exchange auth code for tokens (access_token, refresh_token, expires_in, id_token).
     * Extract email from id_token payload (if present).
     * Returns a Map with keys:
     * - "access_token" -> String
     * - "refresh_token" -> String (may be null)
     * - "expires_in" -> Long
     * - "id_token" -> String (may be null)
     * - "email" -> String (may be null)
     */
    public Map<String, Object> exchangeCodeForTokens(String code) throws Exception {
        String tokenUrl = "https://oauth2.googleapis.com/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);
        ResponseEntity<String> resp = rest.postForEntity(tokenUrl, req, String.class);

        if (resp.getStatusCode().isError()) {
            // include response body for debugging (avoid logging secrets in production)
            throw new RuntimeException("Token exchange failed: " + resp.getStatusCode() + " - " + resp.getBody());
        }

        JsonNode j = mapper.readTree(resp.getBody());
        String accessToken = j.has("access_token") ? j.get("access_token").asText() : null;
        String refreshToken = j.has("refresh_token") ? j.get("refresh_token").asText() : null;
        long expiresIn = j.has("expires_in") ? j.get("expires_in").asLong() : 3600L;
        String idToken = j.has("id_token") ? j.get("id_token").asText() : null;

        String email = null;
        if (idToken != null) {
            // Decode payload from id_token (JWT)
            try {
                String[] parts = idToken.split("\\.");
                if (parts.length >= 2) {
                    String payloadB64 = parts[1];
                    int pad = (4 - (payloadB64.length() % 4)) % 4;
                    payloadB64 += "=".repeat(pad);
                    byte[] decoded = java.util.Base64.getUrlDecoder().decode(payloadB64);
                    String payloadJson = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    JsonNode payload = mapper.readTree(payloadJson);
                    if (payload.has("email")) email = payload.get("email").asText();
                }
            } catch (Exception ignore) {
                // non-fatal — continue without email
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("access_token", accessToken);
        out.put("refresh_token", refreshToken);
        out.put("expires_in", expiresIn);
        out.put("id_token", idToken);
        out.put("email", email);

        return out;
    }

    // Exchange code AFTER email is provided (kept for backwards compatibility).
    // Uses MultiValueMap encoding as well.
    public UserCalendar exchangeCodeAfterEmail(String code, String email) throws Exception {
        Map<String, Object> tokens = exchangeCodeForTokens(code);

        String accessToken = (String) tokens.get("access_token");
        String refreshToken = (String) tokens.get("refresh_token");
        long expiresIn = (Long) tokens.get("expires_in");

        Optional<UserCalendar> opt = repo.findFirstByEmail(email);
        UserCalendar uc = opt.orElseGet(UserCalendar::new);

        uc.setEmail(email);
        uc.setAccessToken(accessToken);
        if (refreshToken != null) uc.setRefreshToken(refreshToken);
        uc.setTokenExpiry(Instant.now().plusSeconds(expiresIn - 60));
        uc.setCalendarId("primary");
        uc.setAuthCode(null);

        repo.save(uc);
        return uc;
    }

    // Auto-refresh if expired
    private void ensureAccessToken(UserCalendar uc) throws Exception {
        if (uc.getAccessToken() == null ||
                uc.getTokenExpiry() == null ||
                Instant.now().isAfter(uc.getTokenExpiry())) {

            if (uc.getRefreshToken() == null)
                throw new RuntimeException("No refresh token available");

            String url = "https://oauth2.googleapis.com/token";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("refresh_token", uc.getRefreshToken());
            form.add("grant_type", "refresh_token");

            HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);
            ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);

            JsonNode j = mapper.readTree(resp.getBody());
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Refresh token exchange failed: " + resp.getBody());
            }

            String newAccess = j.get("access_token").asText();
            long expiresIn = j.get("expires_in").asLong();

            uc.setAccessToken(newAccess);
            uc.setTokenExpiry(Instant.now().plusSeconds(expiresIn - 60));

            repo.save(uc);
        }
    }

    // CREATE EVENT WITH TIMEZONE
    public Map<String, Object> createEvent(String phoneNumber, String title, String dateIso, String time24) throws Exception {
        UserCalendar uc = repo.findFirstByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("No user for phone"));

        ensureAccessToken(uc);

        String timezone = "Asia/Kolkata";
        String start = dateIso + "T" + time24 + ":00+05:30";
        String end = dateIso + "T" + addHour(time24) + ":00+05:30";

        Map<String, Object> event = new HashMap<>();
        event.put("summary", title);
        event.put("start", Map.of("dateTime", start, "timeZone", timezone));
        event.put("end", Map.of("dateTime", end, "timeZone", timezone));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(uc.getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> req = new HttpEntity<>(mapper.writeValueAsString(event), headers);
        String url = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events";
        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);

        JsonNode body = mapper.readTree(resp.getBody());
        return Map.of(
                "id", body.get("id").asText(),
                "htmlLink", body.get("htmlLink").asText()
        );
    }

    // Reschedule, cancel, read events .. (kept same as your existing implementation)
    public Map<String,Object> rescheduleEventByName(String phoneNumber, String eventName, String newDate, String newTime) throws Exception {
        UserCalendar uc = repo.findFirstByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("No user for phone"));

        ensureAccessToken(uc);

        String listUrl = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events";
        HttpHeaders listHeaders = new HttpHeaders();
        listHeaders.setBearerAuth(uc.getAccessToken());
        ResponseEntity<String> listResp = rest.exchange(listUrl, HttpMethod.GET, new HttpEntity<>(listHeaders), String.class);

        JsonNode events = mapper.readTree(listResp.getBody()).get("items");

        String foundEventId = null;
        for (JsonNode ev : events) {
            if (ev.has("summary") && ev.get("summary").asText().equalsIgnoreCase(eventName)) {
                foundEventId = ev.get("id").asText();
                break;
            }
        }

        if (foundEventId == null) {
            throw new RuntimeException("Event not found: " + eventName);
        }

        String start = newDate + "T" + newTime + ":00";
        String end = newDate + "T" + addHour(newTime) + ":00";

        Map<String,Object> updateEvent = new HashMap<>();
        updateEvent.put("start", Map.of("dateTime", start));
        updateEvent.put("end", Map.of("dateTime", end));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(uc.getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> req = new HttpEntity<>(mapper.writeValueAsString(updateEvent), headers);

        String url = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events/" + foundEventId;
        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.PATCH, req, String.class);

        JsonNode body = mapper.readTree(resp.getBody());
        return Map.of("id", body.get("id").asText(), "status", "updated");
    }

    public Map<String, Object> cancelEventByName(String phoneNumber, String eventName, String date, String time) throws Exception {
        UserCalendar uc = repo.findFirstByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("No user for phone"));

        ensureAccessToken(uc);

        String listUrl = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events";
        HttpHeaders listHeaders = new HttpHeaders();
        listHeaders.setBearerAuth(uc.getAccessToken());

        ResponseEntity<String> listResp = rest.exchange(listUrl, HttpMethod.GET, new HttpEntity<>(listHeaders), String.class);
        JsonNode items = mapper.readTree(listResp.getBody()).get("items");

        String matchingEventId = null;
        for (JsonNode ev : items) {
            if (!ev.has("summary") || !ev.has("start")) continue;
            String summary = ev.get("summary").asText();
            String eventStart = ev.get("start").get("dateTime").asText();
            String eventDate = eventStart.substring(0, 10);
            String eventTime = eventStart.substring(11, 16);

            if (summary.equalsIgnoreCase(eventName) && eventDate.equals(date) && eventTime.equals(time)) {
                matchingEventId = ev.get("id").asText();
                break;
            }
        }

        if (matchingEventId == null) {
            throw new RuntimeException("Event not found for cancel: " + eventName);
        }

        String deleteUrl = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events/" + matchingEventId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(uc.getAccessToken());

        rest.exchange(deleteUrl, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        return Map.of(
                "eventId", matchingEventId,
                "eventName", eventName,
                "date", date,
                "time", time
        );
    }

    private String addHour(String time24) {
        String[] parts = time24.split(":");
        int hr = Integer.parseInt(parts[0]);
        int mn = Integer.parseInt(parts[1]);
        hr = (hr + 1) % 24;
        return String.format("%02d:%02d", hr, mn);
    }

    // Read all events (unchanged)
    public List<Map<String, Object>> readEvents(String phoneNumber) throws Exception {
        UserCalendar uc = repo.findFirstByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("No user for phone"));

        ensureAccessToken(uc);

        String url = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(uc.getAccessToken());

        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode root = mapper.readTree(resp.getBody());
        JsonNode items = root.get("items");

        List<Map<String, Object>> events = new ArrayList<>();
        if (items != null) {
            for (JsonNode e : items) {
                if (!e.has("summary") || !e.has("start")) continue;
                String id = e.get("id").asText();
                String title = e.get("summary").asText();
                String start = e.get("start").get("dateTime").asText();
                String end = e.get("end").get("dateTime").asText();
                events.add(Map.of("id", id, "title", title, "start", start, "end", end));
            }
        }
        return events;
    }

    public Map<String, Object> readEventByName(String phoneNumber, String eventName, String date, String time) throws Exception {
        List<Map<String, Object>> events = readEvents(phoneNumber);
        for (Map<String, Object> e : events) {
            String title = (String) e.get("title");
            String start = (String) e.get("start");
            String eventDate = start.substring(0, 10);
            String eventTime = start.substring(11, 16);
            if (title.equalsIgnoreCase(eventName) && eventDate.equals(date) && eventTime.equals(time)) {
                return e;
            }
        }
        throw new RuntimeException("Event not found: " + eventName);
    }

    public Map<String, Object> getCurrentEvent(String phoneNumber) throws Exception {
        UserCalendar uc = repo.findFirstByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("No user for phone"));

        ensureAccessToken(uc);

        String url = "https://www.googleapis.com/calendar/v3/calendars/" + uc.getCalendarId() + "/events";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(uc.getAccessToken());

        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode items = mapper.readTree(resp.getBody()).get("items");

        ZonedDateTime nowIst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

        for (JsonNode ev : items) {
            if (!ev.has("start") || !ev.has("end")) continue;
            String startStr = ev.get("start").get("dateTime").asText();
            String endStr = ev.get("end").get("dateTime").asText();
            ZonedDateTime start = ZonedDateTime.parse(startStr);
            ZonedDateTime end = ZonedDateTime.parse(endStr);

            ZonedDateTime triggerStart = start.minusMinutes(10);
            ZonedDateTime triggerEnd = start.minusMinutes(9);

            if (!nowIst.isBefore(triggerStart) && nowIst.isBefore(triggerEnd)) {
                return Map.of(
                        "eventId", ev.get("id").asText(),
                        "title", ev.get("summary").asText(),
                        "start", startStr,
                        "triggered_at", nowIst.toString(),
                        "trigger_window_start", triggerStart.toString(),
                        "trigger_window_end", triggerEnd.toString()
                );
            }
        }

        return Map.of("message", "No upcoming event (10-minute trigger not reached)");
    }
}
