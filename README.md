Mira Complete Project (Frontend + Backend)
=========================================
1. Update backend/src/main/resources/application.yml:
   - google.clientId
   - google.clientSecret
   - redirectUri (should match your Google Cloud OAuth 2.0 client redirect URI)
   - frontendRedirect (URL where frontend connect page is running, default http://localhost:3000/connect)

2. Start backend:
   cd backend
   mvn clean install
   mvn spring-boot:run

3. Start frontend:
   cd frontend
   npm install
   npm start

Flow:
- Click 'Sign in with Google' -> backend returns Google OAuth URL -> frontend redirects
- After consent Google calls backend /api/oauth2/callback -> tokens stored
- Frontend receives redirect to /connect?email=... -> enter phone number and link
- Use Call Mira to POST webhooks using the phone number; backend will call Google Calendar for that user.
