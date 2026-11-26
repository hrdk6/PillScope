from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter(tags=["legal"])

_PRIVACY_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>PillScope Privacy Policy &amp; Medical Disclaimer</title>
  <style>
    body {
      font-family: system-ui, -apple-system, sans-serif;
      max-width: 760px; margin: 2rem auto; padding: 0 1.2rem;
      line-height: 1.6; color: #0f1e22;
    }
    h1, h2 { color: #063b3a; }
    .callout {
      background: #fef4e4; border-left: 4px solid #c46a08;
      padding: 1rem; border-radius: 8px;
    }
  </style>
</head>
<body>
  <h1>PillScope Privacy Policy &amp; Medical Disclaimer</h1>
  <div class="callout">
    <strong>Medical Disclaimer:</strong> PillScope provides informational medication safety checks
    and is not a substitute for professional medical advice, diagnosis, or treatment. Always consult
    a licensed physician or pharmacist before starting, stopping, or changing any medication.
  </div>
  <h2>Data We Collect</h2>
  <p>We store your account email, password hash, family profile names/relationships, documented
     drug allergies, active medication cabinet entries, and saved scan logs so we can perform
     drug-interaction, duplicate-salt, allergy, dosing schedule, and recall checks.</p>
  <h2>Third-Party AI &amp; Safety Processing</h2>
  <p>When you run a medication safety scan, the medicine query, resolved active ingredients,
     relevant cabinet active ingredients, and triggered allergy or interaction alerts may be sent
     to configured clinical briefing AI providers (such as Google Gemini or OpenAI) solely to
     synthesize grounded explanations from official openFDA/DailyMed monographs. Camera images are
     processed on-device and are not uploaded.</p>
  <h2>Data Retention &amp; Deletion</h2>
  <p>You can permanently delete your account and all associated profiles, medication cabinets,
     allergies, and scan history at any time in the PillScope app (Profile &rarr; Account &rarr;
     Delete Account) or via <a href="/account-deletion">/account-deletion</a>.</p>
</body>
</html>
"""

_DELETION_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>PillScope Account &amp; Data Deletion</title>
  <style>
    body {
      font-family: system-ui, -apple-system, sans-serif;
      max-width: 760px; margin: 2rem auto; padding: 0 1.2rem;
      line-height: 1.6; color: #0f1e22;
    }
    h1, h2 { color: #063b3a; }
    code { background: #edf3f3; padding: 2px 6px; border-radius: 4px; }
  </style>
</head>
<body>
  <h1>Delete Your PillScope Account &amp; Health Data</h1>
  <p>You may permanently delete your PillScope account and all associated health records
     at any time.</p>
  <h2>Option 1: In the PillScope Android App</h2>
  <ol>
    <li>Open PillScope and tap your active profile pill at the top of the screen.</li>
    <li>Scroll to the <strong>Account &amp; Privacy</strong> section.</li>
    <li>Tap <strong>Delete Account &amp; Data</strong> and confirm permanent deletion.</li>
  </ol>
  <h2>Option 2: Authenticated API Request</h2>
  <p>Send an authenticated <code>DELETE /api/v1/auth/me</code> request with your Bearer access
     token. Your user record, family profiles, medication cabinets, documented allergies, and
     scan logs are permanently removed immediately via cascading deletion.</p>
</body>
</html>
"""


@router.get("/privacy", response_class=HTMLResponse, include_in_schema=False)
async def privacy_policy() -> HTMLResponse:
    return HTMLResponse(content=_PRIVACY_HTML)


@router.get("/account-deletion", response_class=HTMLResponse, include_in_schema=False)
async def account_deletion_instructions() -> HTMLResponse:
    return HTMLResponse(content=_DELETION_HTML)
