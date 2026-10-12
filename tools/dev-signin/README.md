# Dev sign-in page

A throwaway page that prints a real Google ID token, so the backend's auth endpoints can be
tested with `curl` before the sign-in UI exists (phase 7).

**It is never deployed.** It lives in `tools/` rather than `client/` precisely so it cannot be
picked up by a Vercel build, and it makes no request to the Medi-Scan API — it only talks to
Google and shows you what came back.

## One-time Google setup

In the Google Cloud console, on the OAuth **web application** client you use for development:

1. Add `http://localhost:5500` as an **authorised JavaScript origin**.
2. Remove it again when you have finished. It is a development origin on a shared client, and
   leaving it there is a loose end.

No client secret is involved. The ID token flow needs only the client id, which is a public
value that ships in the frontend bundle anyway.

## Serving it

```bash
python3 -m http.server 5500 --directory tools/dev-signin
```

Then open <http://localhost:5500>, paste the client id into the field, press **Load**, and sign
in. The token appears in the text box.

The client id is typed in rather than committed, so this file holds no configuration for any
real project.

## The manual run

With the backend running under the `local` profile and `GOOGLE_CLIENT_ID` set in `.env` to the
same client id:

```bash
API=http://localhost:8080

# 1. Start as a guest and upload a sample, so there is something to claim.
GUEST=$(curl -sS -X POST $API/api/sessions | jq -r .token)
curl -sS -X POST $API/api/reports \
  -H "X-Session-Token: $GUEST" -H 'Content-Type: application/json' \
  -d '{"sampleId":"lipid-panel","consent":true}'

# 2. Paste the ID token from the page. Read it from the terminal rather than putting it in a
#    command you might later paste somewhere - it is a credential.
read -r -s ID_TOKEN

# 3. Sign in, claiming the guest's report. Expect claimedReportCount: 1.
curl -sS -X POST $API/api/auth/google \
  -H 'Content-Type: application/json' \
  -d "{\"idToken\":\"$ID_TOKEN\",\"guestSessionToken\":\"$GUEST\"}" | tee /tmp/signin.json

ACCESS=$(jq -r .accessToken /tmp/signin.json)
REFRESH=$(jq -r .refreshToken /tmp/signin.json)

# 4. Your own account. Expect your display name and reportCount: 1.
curl -sS $API/api/me -H "Authorization: Bearer $ACCESS"

# 5. Rotate, then replay the old token. The first succeeds; the second is 401
#    REFRESH_TOKEN_INVALID, and the new token it issued is revoked with it.
NEW_REFRESH=$(curl -sS -X POST $API/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}" | jq -r .refreshToken)
curl -sS -i -X POST $API/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}" | head -1
curl -sS -i -X POST $API/api/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$NEW_REFRESH\"}" | head -1

# 6. Sign in again for a fresh access token, then delete the account. The old access token
#    stops working immediately, not in fifteen minutes.
read -r -s ID_TOKEN
ACCESS=$(curl -sS -X POST $API/api/auth/google -H 'Content-Type: application/json' \
  -d "{\"idToken\":\"$ID_TOKEN\"}" | jq -r .accessToken)
curl -sS -i -X DELETE $API/api/me -H "Authorization: Bearer $ACCESS" | head -1
curl -sS -i $API/api/me -H "Authorization: Bearer $ACCESS" | head -1   # 401 AUTH_REQUIRED
```

## Making yourself admin

1. Sign in once, so the account exists.
2. Read your Google subject straight from the database — it is deliberately not in any API
   response:

   ```bash
   docker compose exec postgres psql -U postgres -d mediscan \
     -c "select google_sub, display_name, role from app_user;"
   ```

3. Put that value in `ADMIN_GOOGLE_SUBS` in `.env` and restart the backend.
4. Refresh your token. The new access token carries `role: ADMIN` — you do not need to sign in
   again, because the role is recomputed from config on every refresh.

There is no endpoint and no UI that grants the role; this is the only way.

## Checking the logs afterwards

Nothing above should have put a credential in a log file. To confirm, with the backend's output
captured to `backend.log`:

```bash
grep -c -F -e "$ID_TOKEN" -e "$ACCESS" -e "$REFRESH" -e "$GUEST" backend.log   # expect 0
grep -ci "your-google-sub-here" backend.log                                    # expect 0
grep -ci "your display name" backend.log                                       # expect 0
```

Do not raise `org.springframework.jdbc`, `org.postgresql` or `org.hibernate` to `TRACE` while
doing this: those loggers print bound SQL parameter values by design, which includes
`google_sub` and `display_name`. See the "Accounts" section of `SECURITY.md`.
