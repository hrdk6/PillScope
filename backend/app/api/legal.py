import html
from typing import Annotated
from urllib.parse import parse_qs

import structlog
from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.security import verify_password
from backend.app.db.models import RevokedRefreshToken, User
from backend.app.db.session import get_db_session

logger = structlog.get_logger(__name__)
router = APIRouter(tags=["legal"])

_PRIVACY_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="color-scheme" content="light dark" />
  <title>PillScope Privacy Policy &amp; Medical Disclaimer</title>
  <style>
    :root {
      --bg: #f5f6f3;
      --surface: #ffffff;
      --ink: #18201d;
      --muted: #535f5a;
      --heading: #13342c;
      --accent: #1f4f43;
      --border: #d4d9d2;
      --callout-bg: #f7efe3;
      --callout-border: #9a5a16;
      --callout-ink: #693b0b;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #131716;
        --surface: #1b211f;
        --ink: #ecefe9;
        --muted: #9ca9a4;
        --heading: #c7ddd5;
        --accent: #7fb3a2;
        --border: #303b37;
        --callout-bg: #312617;
        --callout-border: #d4a05a;
        --callout-ink: #efe0c6;
      }
    }
    body {
      font-family: system-ui, -apple-system, sans-serif;
      max-width: 720px; margin: 2.5rem auto; padding: 0 1.25rem;
      line-height: 1.7; background: var(--bg); color: var(--ink);
    }
    main {
      background: var(--surface); border: 1px solid var(--border);
      border-radius: 16px; padding: 2rem;
    }
    h1, h2 {
      font-family: Georgia, "Times New Roman", serif;
      font-weight: 600;
      letter-spacing: -0.015em;
      color: var(--heading);
    }
    a { color: var(--accent); text-underline-offset: 3px; }
    .callout {
      background: var(--callout-bg); border-left: 3px solid var(--callout-border);
      color: var(--callout-ink); padding: 1rem 1.15rem; border-radius: 10px;
    }
  </style>
</head>
<body>
  <main>
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
       Delete Account) or directly in your browser at
       <a href="/account-deletion">/account-deletion</a>.</p>
  </main>
</body>
</html>
"""


def _render_deletion_page(
    *,
    status_message: str | None = None,
    error_message: str | None = None,
    prefill_email: str = "",
) -> str:
    banner = ""
    if status_message:
        banner = f'<div class="banner success" role="status">{html.escape(status_message)}</div>'
    elif error_message:
        banner = f'<div class="banner error" role="alert">{html.escape(error_message)}</div>'

    escaped_email = html.escape(prefill_email, quote=True)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="color-scheme" content="light dark" />
  <title>PillScope Account &amp; Data Deletion</title>
  <style>
    :root {{
      --bg: #f5f6f3;
      --surface: #ffffff;
      --form-bg: #eaece7;
      --ink: #18201d;
      --heading: #13342c;
      --border: #d4d9d2;
      --input-bg: #ffffff;
      --input-border: #a5b0a9;
      --code-bg: #e3e7e1;
      --danger: #921b24;
      --success-bg: #e7f0eb;
      --success-ink: #1a4531;
      --success-border: #286448;
      --error-bg: #f8e8e9;
      --error-ink: #6d131a;
      --error-border: #9e1f28;
    }}
    @media (prefers-color-scheme: dark) {{
      :root {{
        --bg: #131716;
        --surface: #1b211f;
        --form-bg: #232b28;
        --ink: #ecefe9;
        --heading: #c7ddd5;
        --border: #303b37;
        --input-bg: #131716;
        --input-border: #44534e;
        --code-bg: #27312e;
        --danger: #8c1e26;
        --success-bg: #192c22;
        --success-ink: #cce3d6;
        --success-border: #7bb295;
        --error-bg: #351b1e;
        --error-ink: #f3cdd0;
        --error-border: #d9787e;
      }}
    }}
    body {{
      font-family: system-ui, -apple-system, sans-serif;
      max-width: 720px; margin: 2.5rem auto; padding: 0 1.25rem;
      line-height: 1.7; background: var(--bg); color: var(--ink);
    }}
    main {{
      background: var(--surface); border: 1px solid var(--border);
      border-radius: 16px; padding: 2rem;
    }}
    h1, h2 {{
      font-family: Georgia, "Times New Roman", serif;
      font-weight: 600;
      letter-spacing: -0.015em;
      color: var(--heading);
    }}
    code {{ background: var(--code-bg); padding: 2px 6px; border-radius: 4px; }}
    form {{
      background: var(--form-bg); border: 1px solid var(--border); border-radius: 12px;
      padding: 1.25rem; margin: 1.25rem 0;
    }}
    label {{ display: block; font-weight: 600; margin-top: 0.75rem; }}
    input {{
      width: 100%; box-sizing: border-box; padding: 0.65rem 0.75rem;
      margin-top: 0.25rem; border: 1px solid var(--input-border); border-radius: 8px;
      font-size: 1rem; background: var(--input-bg); color: var(--ink);
    }}
    button {{
      margin-top: 1rem; padding: 0.75rem 1.25rem; border: 0; border-radius: 10px;
      background: var(--danger); color: #ffffff; font-weight: 600; font-size: 1rem; cursor: pointer;
    }}
    .banner {{ padding: 0.9rem 1rem; border-radius: 10px; margin: 1rem 0; font-weight: 500; }}
    .banner.success {{ background: var(--success-bg); color: var(--success-ink); border: 1px solid var(--success-border); }}
    .banner.error {{ background: var(--error-bg); color: var(--error-ink); border: 1px solid var(--error-border); }}
  </style>
</head>
<body>
  <main>
    <h1>Delete Your PillScope Account &amp; Health Data</h1>
    <p>You may permanently delete your PillScope account and all associated family profiles,
       medication cabinets, documented allergies, and scan logs immediately.</p>
    {banner}
    <h2>Option 1: Delete Immediately via Web Form</h2>
    <form method="post" action="/account-deletion">
      <label for="email">Account Email</label>
      <input id="email" name="email" type="email" required autocomplete="email" value="{escaped_email}" />
      <label for="password">Account Password</label>
      <input id="password" name="password" type="password" required autocomplete="current-password" />
      <label for="confirm">Type <code>DELETE</code> to confirm permanent removal</label>
      <input id="confirm" name="confirm" type="text" required placeholder="DELETE" />
      <button type="submit">Permanently Delete Account &amp; All Data</button>
    </form>
    <h2>Option 2: In the PillScope Android App</h2>
    <ol>
      <li>Open PillScope and tap your active profile pill at the top of the screen.</li>
      <li>Scroll to the <strong>Account &amp; Privacy</strong> section.</li>
      <li>Tap <strong>Delete Account</strong>, type <code>DELETE</code>, and confirm.</li>
    </ol>
    <h2>Option 3: Authenticated API Request</h2>
    <p>Send an authenticated <code>DELETE /api/v1/auth/me</code> request with your Bearer access
       token. All user data is permanently deleted immediately via cascading deletion.</p>
  </main>
</body>
</html>
"""


@router.get("/privacy", response_class=HTMLResponse, include_in_schema=False)
async def privacy_policy() -> HTMLResponse:
    return HTMLResponse(content=_PRIVACY_HTML)


@router.get("/account-deletion", response_class=HTMLResponse, include_in_schema=False)
async def account_deletion_instructions() -> HTMLResponse:
    return HTMLResponse(content=_render_deletion_page())


@router.post("/account-deletion", response_class=HTMLResponse, include_in_schema=False)
async def account_deletion_submit(
    request: Request,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HTMLResponse:
    raw_body = (await request.body()).decode("utf-8", errors="ignore")
    parsed = parse_qs(raw_body, keep_blank_values=False)
    email = (parsed.get("email", [""])[0]).strip().lower()
    password = parsed.get("password", [""])[0]
    confirm = (parsed.get("confirm", [""])[0]).strip()

    if confirm.upper() != "DELETE":
        return HTMLResponse(
            content=_render_deletion_page(
                error_message="Please type DELETE in the confirmation field to proceed.",
                prefill_email=email,
            ),
            status_code=400,
        )
    if not email or not password:
        return HTMLResponse(
            content=_render_deletion_page(
                error_message="Email and password are required.",
                prefill_email=email,
            ),
            status_code=400,
        )

    user = await session.scalar(select(User).where(User.email == email))
    if user is None or not verify_password(password, user.password_hash):
        return HTMLResponse(
            content=_render_deletion_page(
                error_message="Invalid email or password.",
                prefill_email=email,
            ),
            status_code=401,
        )

    user_id = user.id
    await session.execute(delete(RevokedRefreshToken).where(RevokedRefreshToken.user_id == user_id))
    await session.delete(user)
    await session.commit()
    logger.info("user_account_deleted_via_web_form", user_id=str(user_id))

    return HTMLResponse(
        content=_render_deletion_page(
            status_message=(
                "Your PillScope account and all associated family profiles, medications, "
                "allergies, and scan logs have been permanently deleted."
            )
        ),
        status_code=200,
    )
