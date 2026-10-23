# T57 E5 touch-coordinate repair

Scope: **Buick-E5 only**. Evidence from 2026-10-23 Sony XQ-DQ72 B233:

- /info TouchDescriptor announces HID X max 3082 (0x0C0A), Y max 814 (0x032E).
- CarPlay video canvas is 1972x520. E5 SessionController previously mapped view->stream 1:1 instead of HID->stream.
- Uploaded examples: raw pointer (581,758) was treated as view pixels and would be beyond 520px. Actual touch failed at display 6 coordinate (327,733), an invalid Y.
- Buick-Test/main already called TouchRouter.setCoordinateScale with descriptor-derived hidMaxX/Y, but the E5 fork did not.

Fix: port TouchReports.logicalMaximaFromDescriptor and its Buick regression tests verbatim from Buick-Test; derive per-session maxima from the actual advertised Digitizer descriptor, normalize to output stream, reset stale gesture state on a newly established session, and never latch a pressed pointer after rejected injection. No audio, network, app identity, or display-creation changes. Successful Android CI build will **not** prove on-vehicle touch accuracy; manual car validation required. Note: device reboot risk under investigation separately.
