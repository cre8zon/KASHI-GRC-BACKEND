# In-app calls — LiveKit on calls.kashiguard.com

KashiGRC calls run on your own LiveKit server, on the same droplet as the app
(64.227.182.108), behind the existing nginx. Nothing is recorded; media stays
between participants and this server.

```
browser ──wss──▶ nginx :443 (calls.kashiguard.com) ──▶ LiveKit :7880   signalling
browser ──UDP──▶ LiveKit 50000–60000 / TCP 7881                         media
browser ──TLS──▶ LiveKit :5349 / UDP 3478 (turn.calls.kashiguard.com)   relay, strict networks
backend ──https─▶ calls.kashiguard.com/twirp/…                          who is in a room
```

No application code changes. The backend reads `livekit.url / api-key /
api-secret`; the browser gets the URL from the backend.

---

## Step 1 — DNS at GoDaddy (kashiguard.com)

1. GoDaddy → **My Products** → kashiguard.com → **DNS** (Manage DNS).
   - If GoDaddy says the nameservers are not GoDaddy's (e.g. they point to
     DigitalOcean), add the records in **that** provider instead — same values.
2. **Add New Record** twice:

   | Type | Name         | Value            | TTL        |
   |------|--------------|------------------|------------|
   | A    | `calls`      | `64.227.182.108` | 600 sec    |
   | A    | `turn.calls` | `64.227.182.108` | 600 sec    |

   Name is only the part before `.kashiguard.com`. No CNAME, no proxy.
3. Wait a few minutes, then from your PC:
   ```
   nslookup calls.kashiguard.com
   nslookup turn.calls.kashiguard.com
   ```
   Both must answer `64.227.182.108`. Do not continue until they do —
   the certificate in step 3 needs them.

## Step 2 — Firewall (on the droplet AND in DigitalOcean)

On the droplet (`ssh root@64.227.182.108`):
```bash
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 7881/tcp            # media over TCP
ufw allow 5349/tcp            # TURN over TLS
ufw allow 3478/udp            # TURN over UDP
ufw allow 50000:60000/udp     # media over UDP
ufw allow 30000:40000/udp     # TURN relay
ufw status                    # 7880 must NOT be listed — nginx fronts it
```
DigitalOcean → **Networking → Firewalls**: if a cloud firewall is attached to
the droplet, add the same inbound rules there (it blocks before ufw does).

## Step 3 — Certificate (one cert for both names)

```bash
apt install -y certbot python3-certbot-nginx     # skip if already installed
certbot certonly --nginx \
  -d calls.kashiguard.com -d turn.calls.kashiguard.com \
  --deploy-hook "systemctl reload nginx; docker restart livekit"
ls /etc/letsencrypt/live/calls.kashiguard.com/   # fullchain.pem, privkey.pem
```
The deploy hook makes renewals reload nginx and LiveKit (TURN reads the cert
at start-up). Test renewal later with `certbot renew --dry-run`.

## Step 4 — nginx

A new, separate site next to the existing frontend/backend sites — those are
not touched. Create `/etc/nginx/sites-available/calls.kashiguard.com` with:

```nginx
# /etc/nginx/sites-available/calls.kashiguard.com
# Enable: ln -s /etc/nginx/sites-available/calls.kashiguard.com /etc/nginx/sites-enabled/
#
# Certificate (one, covering both names) is issued BEFORE this file is enabled:
#   certbot certonly --nginx -d calls.kashiguard.com -d turn.calls.kashiguard.com

# WebSocket upgrade only when the client asks for it. Own name, so it cannot
# clash with a $connection_upgrade map your app's config may already define.
map $http_upgrade $lk_connection_upgrade {
    default upgrade;
    ''      close;
}

# HTTP → HTTPS, and the path certbot uses to renew.
server {
    listen 80;
    listen [::]:80;
    server_name calls.kashiguard.com turn.calls.kashiguard.com;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }
    location / {
        return 301 https://$host$request_uri;
    }
}

# LiveKit signalling (wss) and its HTTP API (the backend's participant count).
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name calls.kashiguard.com;

    ssl_certificate     /etc/letsencrypt/live/calls.kashiguard.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/calls.kashiguard.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:7880;
        proxy_http_version 1.1;
        proxy_set_header Upgrade           $http_upgrade;
        proxy_set_header Connection        $lk_connection_upgrade;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # A call is one long-lived WebSocket — do not cut it after 60 s.
        proxy_read_timeout  86400s;
        proxy_send_timeout  86400s;
        proxy_buffering     off;
    }
}
# turn.calls.kashiguard.com has no HTTPS block on purpose: TURN is not HTTP.
# Clients reach LiveKit's TURN directly on TCP 5349 / UDP 3478.
```

Then:
```bash
ln -s /etc/nginx/sites-available/calls.kashiguard.com /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```
(If the server keeps its sites in `/etc/nginx/conf.d/` instead, save it there as
`calls.kashiguard.com.conf` and skip the `ln -s`.)
If `nginx -t` complains about a duplicate `map`, your app config already
defines one with the same variable — this file uses `$lk_connection_upgrade`,
so check you copied it unchanged.

## Step 5 — LiveKit

```bash
apt install -y docker.io docker-compose-plugin  # skip if Docker is already there
mkdir -p /opt/livekit && cd /opt/livekit
# copy livekit.yaml and docker-compose.yml from this folder into /opt/livekit
```
Edit `/opt/livekit/livekit.yaml` — the `keys:` line:
```yaml
keys:
  kashikey: <the SAME value as the backend's LIVEKIT_API_SECRET>
```
- The key name stays `kashikey` — the backend already signs tokens with it.
- If the backend has no secret yet: `openssl rand -hex 32`, use it in both places.

Start it:
```bash
docker compose up -d
docker logs -f livekit        # expect "starting LiveKit server" and the TURN line; Ctrl+C to leave
```

## Step 6 — Backend

Set on the backend (environment, or `application.properties` — whichever your
deployment uses):
```
LIVEKIT_URL=wss://calls.kashiguard.com
LIVEKIT_API_KEY=kashikey
LIVEKIT_API_SECRET=<same secret as livekit.yaml>
```
Properties-file form: `livekit.url=…`, `livekit.api-key=…`, `livekit.api-secret=…`.
Restart the backend.

If the app's nginx sends a `Content-Security-Policy` header, its `connect-src`
must include `wss://calls.kashiguard.com https://calls.kashiguard.com`
(`grep -ri content-security /etc/nginx` to check).

## Step 7 — Check

1. Browser: `https://calls.kashiguard.com` → shows **OK**.
2. App → Meetings → **Call now** → you see yourself; join from a second
   account → both see each other.
3. Problems, in order of likelihood:
   | Symptom | Look at |
   |---|---|
   | "Can't reach the call server at wss://calls.dinfosek.com" | backend still has the old URL — step 6, restart |
   | Step 7.1 fails / certificate error | DNS (step 1), nginx (step 4) |
   | Connects, then no video/audio | UDP ports (step 2), DO cloud firewall |
   | Works at home, not on office Wi-Fi | TURN: 5349/3478 open, `docker logs livekit` |
   | "invalid token" / 401 | key name or secret differs between livekit.yaml and backend |

## Maintenance
- Update: `cd /opt/livekit && docker compose pull && docker compose up -d`.
- Secret lives only in `livekit.yaml` on the droplet and the backend's config.
- Tokens are issued per room after KashiGRC checks access and last 15 minutes
  (`LIVEKIT_TOKEN_TTL_SECONDS`).
- Include the call server in your next penetration test.
