Mira Backend
============
- Update src/main/resources/application.yml with your Google OAuth client ID and secret.
- Run: mvn spring-boot:run
- Endpoints:
  GET /api/auth/google -> returns URL to redirect to Google OAuth
  GET /api/oauth2/callback -> callback that stores tokens and redirects to frontend connect page
  POST /api/connect-phone -> { email, phoneNumber }
  POST /api/webhook/create -> { phoneNumber, date, time, title }
  POST /api/webhook/reschedule -> { phoneNumber, eventId, newDate, newTime }
  POST /api/webhook/cancel -> { phoneNumber, eventId }
