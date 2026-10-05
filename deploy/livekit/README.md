# In-app calls — LiveKit server

KashiGRC calls run on your own LiveKit server. Nothing is recorded; media
stays between participants and this server.

## 1. Server (a small VM is enough to start: 2 vCPU / 4 GB)
1. Point DNS `calls.example.com` and `turn.calls.example.com` at the VM.
2. Open firewall: TCP 80, 443, 7881, 5349 · UDP 3478, 30000–40000 (TURN relay), 50000–60000.
3. Edit `livekit.yaml` (key/secret, TURN domain) and `Caddyfile` (domains).
4. `docker compose up -d`. Check: `https://calls.example.com` answers "OK".

## 2. Backend (environment variables)
    LIVEKIT_URL=wss://calls.example.com
    LIVEKIT_API_KEY=<the key from livekit.yaml>
    LIVEKIT_API_SECRET=<its secret, 32+ characters>
Restart the backend. Without these, calls stay switched off and the UI hides them.

## 3. Frontend
    npm install livekit-client@^2 @livekit/components-react@^2 @livekit/components-styles

## Security checklist
- Keep the secret only in the server config and the backend's secret store.
- Tokens are issued per room after KashiGRC checks access, last 15 minutes
  (`LIVEKIT_TOKEN_TTL_SECONDS`), and are refreshed by LiveKit for people
  already in the call.
- Update the image regularly (`docker compose pull && docker compose up -d`).
- Have the deployment included in your next penetration test.
