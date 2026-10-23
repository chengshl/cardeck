# T48 server-side timestamp redaction parity

Scope: Buick-main gateway.py source only. 2026-10-23.

The phone T46/T47 protected clock timestamps, but Gateway double-redacted uploaded
logs and replaced HH:mm:ss with [IPv6]. Preserve clock timestamps on the Gateway
second pass while still masking compressed IPv6 and other sensitive identifiers.
Tests cover Android logcat, DropBox +0800, ISO UTC, and IPv6 examples.

Note: source commit does not update live ConfigMaps/deployments. Gateway service
must be deployed separately; MCP/Gateway remain read-only to this assistant.
No vehicle or CarPlay audio protocol changes.
