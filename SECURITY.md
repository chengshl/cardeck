# Public repository security policy

This repository is public. Never commit real service origins, private DNS names,
physical storage mounts, internal IP/MAC identifiers, tokens, certificates,
signing keys, or rendered Kubernetes manifests with site-specific values.

- Public Kubernetes manifests are **examples**, not complete production manifests.
  Replace `REPLACE_WITH_PRIVATE_...` values only inside private deployment automation.
- Configure browser Origin restrictions using `CARDECK_GATEWAY_ALLOWED_ORIGINS`
  in private runtime settings. The native device WSS client sends no Origin.
- Inject device/upload and read-only-query credentials through separate
  Kubernetes Secrets. Never check the contents into Git or publish them in issues.
- Store signing material in GitHub Actions Secrets or a private secret manager.
- Run `python3 tools/security/public_repo_guard.py` locally; CI checks every branch.
- Avoid posting raw phone diagnostic logs; use the existing redaction and
  user-confirmed upload flow.
- Use a GitHub `users.noreply.github.com` email for future commits (configure
  the Git author's email and GitHub email privacy). This does not scrub
  existing commit metadata.

The guard checks the **current tracked tree**, not every historical Git object.
If a real credential was ever published, revoke/rotate it first. Removing it
from today's branch is not sufficient. Do not rewrite shared Git history without
coordinating with repository consumers and release automation.

## Personal privacy in future contributions (user-owned fork)

- Treat exact vehicle Bluetooth/Wi-Fi names, device identifiers, real IP/MAC values, QR/pairing codes, remote-access URLs, private DNS names, storage mounts, and diagnostics as private. In code comments and docs replace them with `[CAR_BT_NAME]`, `[IP]`, `[MAC]`, `[PRIVATE_URL]` or `[PRIVATE_PATH]`.
- Do not redact protocol constants, public upstream examples, model identifiers (such as B233), or synthetic unit-test fixtures that are not linked to the user. Prefer documentation-only address ranges and synthetic identifiers for new test cases.
- Never commit or attach raw CarDeck application logs, bugreports, HCI captures, certificates, signing files or Gateway access tokens. Use the app's log redactor, then manually verify any logs proposed for publication. The diagnostic upload service is separate from GitHub.
- CI fails when a tracked file contains a user-specific vehicle identifier or common private infrastructure/credential patterns; CI prints only file paths and finding categories.
- Check commit titles/descriptions and PR or issue attachments separately: these are not covered by the tracked-file scanner. Do not include sensitive values in commit messages.
- Use GitHub's no-reply author email for future commits. The workflow warns without disclosing the email; account settings and author identity cannot be changed with repository file edits alone.
- Do not rewrite historical upstream commits or force-push old tags as part of routine maintenance. Escalate only upon verified exposure of a secret requiring revocation.
