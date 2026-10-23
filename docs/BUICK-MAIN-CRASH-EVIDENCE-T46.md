# Buick-main T46: dated crash evidence and timestamps

Date: 2026-10-23. Scope Buick-main only.

- Preserve HH:mm:ss and HH:mm:ss.SSS clock times through IPv6 redaction; maintain masking of real IPs, device identifiers and secrets.
- Parse newest dated DropBox entry per tag, prioritize first exception, caused-by and stack rather than repeated metadata or arbitrary final 8 lines.
- Keep logcat -L in threadtime/year format where supported; absence of prior boot logs remains possible.
- Upload only explicitly previewed and confirmed redacted evidence, maximum 64 lines of 256 characters each.
- Deduplicate by dated event ID when available; warn when event timestamp is unavailable and never treat read time as crash occurrence time.
- Additional unit tests cover timestamp preservation, IPv6 masking, latest DropBox selection and undated evidence.
