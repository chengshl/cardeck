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
